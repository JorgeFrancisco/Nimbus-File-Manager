# A8 — Processamento em worker separado com fila no PostgreSQL

> **ARQUIVADO — o A8 está concluído.** Este documento é o **plano e a evolução histórica** da
> separação App × Worker: por que cada decisão foi cogitada, o que a revisão mudou e como o desenho
> chegou onde chegou. Ele permanece íntegro porque apagá-lo apagaria o raciocínio junto com o plano.
>
> **Não leia isto para saber como o sistema funciona hoje.** Para isso:
>
> | Pergunta | Onde |
> | --- | --- |
> | Como a arquitetura funciona hoje | [`docs/architecture/worker-architecture.md`](../architecture/worker-architecture.md) |
> | Por que as decisões permanentes foram tomadas | [`docs/adr/`](../adr/) — ADRs 0003 a 0008 |
> | Como se chegou até aqui, fatia a fatia | [`a8-auditoria-de-aderencia.md`](a8-auditoria-de-aderencia.md) |

Análise do código em 2026-08-03, versão 6.3.1.166. Documento de **proposta**: quando foi escrito,
nada aqui estava implementado.

Segunda passada, revisando 25 pontos levantados sobre a primeira versão. Onde a revisão mudou a
conclusão anterior, isso está dito explicitamente.

---

## 1. Como o processamento funciona hoje

### 1.1 Três mecanismos de execução paralela que não se conhecem

**a) `@Async` sobre três pools (`AsyncConfig`)**

| Bean | Core/Max | Fila | Quem usa |
| --- | --- | --- | --- |
| `nimbusFileManagerTaskExecutor` | 2/4 | 50 | inventário, organização, backup, conversão, dedup, metadata, update, tools |
| `nimbusFileManagerGeolocationExecutor` | 2/2 | 20 | geolocalização |
| `nimbusFileManagerVisualAnalysisExecutor` | 2/2 | 20 | pHash, similaridade, fingerprints |

Catorze `*AsyncRunner` disparam trabalho por aí.

**b) `ProcessingCoordinator`** — `ThreadPoolExecutor` de `workers` fixos com fila limitada
(`nimbus-file-manager.processing.*`) e backpressure real: quando satura, **bloqueia o chamador**. Hoje
só o inventário usa.

**c) Seis timers com `ScheduledExecutorService` próprio** — `UpdateCheckScheduler`,
`GeoDatasetAutoUpdateScheduler`, `ReconcileScheduler`, `QuarantinePurgeScheduler`,
`CatalogFilePurgeScheduler`, `InventoryWatchService`. Não há `@EnableScheduling`.

E **Spring Batch** no inventário (`InventoryJobConfig`), lançado à mão por
`InventoryBatchLauncherService`.

### 1.2 Estado: metade no banco, metade na memória

**No banco** (`Execution`): tipo, status, gatilho, caminhos, tempos, contadores (`filesFound`,
`filesAnalyzed`, `cacheHits`, `filesMoved`, `simulatedFiles`, `errors`, `totalExpected`),
`statusMessage`, `applicationVersion`. Mais `ExecutionStep` e `Movement`.

**Na memória**: `ExecutionCancellationService` (`ConcurrentHashMap<Long, AtomicBoolean>`), um
`AtomicBoolean running` por runner, `InventoryWatchService.inventoryPending`,
`SelfWrittenPathRegistry` e o próprio `OperationLockService`.

**O progresso já é persistente**: `execution-progress.js` faz polling em `/api/executions/{id}` a cada
1,5–3 s, servido do banco. Sem WebSocket. **Isso continua funcionando com o executor em outro
processo, sem uma linha alterada** — é o que mais barateia esta mudança.

### 1.3 Recuperação hoje

`StartupExecutionRecoveryListener` marca como `INTERRUPTED` tudo que ficou em estado de progresso,
porque *"there's no way to actually resume a scan mid-way (no cursor is persisted)"*.

---

## 2. Lifecycle App × Worker: resolvendo a contradição

A primeira versão dizia, em pontos diferentes, que o Worker **continua** se a App morrer e que ele
**morre junto**. As duas não podem valer.

### 2.1 Quem é o supervisor de verdade

O código responde: **é a JVM da App, via Spring**, não o launcher nem a bandeja.

- `TrayLifecycle` documenta que *"Ending this process abruptly leaves the embedded PostgreSQL running
  - it is stopped by a shutdown handler - so 'Exit' has to go through Spring rather than through
  `System.exit`"*, e faz `System.exit(SpringApplication.exit(context, () -> 0))`.
- `ApplicationShutdown` existe como port justamente porque *"the embedded PostgreSQL is stopped by a
  shutdown handler, so a process that simply exits leaves a database server behind"*.
- `EmbeddedClusterService.stop()` roda no ciclo do contexto.

Ou seja: **o contexto Spring da App já é o supervisor de processo filho, e o launcher do jpackage é
apenas quem o inicia**. O Worker entra nesse mesmo lugar — mais um filho parado pelo mesmo caminho.

### 2.2 Decisão consciente por cenário

| Cenário | Worker | Por quê |
| --- | --- | --- |
| Usuário fecha pela bandeja | shutdown ordenado (§9) | é o caminho que já para o PostgreSQL |
| Atualização | shutdown ordenado **antes** de a App sair | o MSI falha se o Worker segurar arquivos — foi o defeito que travou a atualização de hoje |
| Uninstall | idem | mesma razão |
| **Crash da App** | **Worker termina o job corrente e encerra** | ver abaixo |
| Morte do tray | irrelevante: a bandeja é UI, não dona do processo | `ApplicationTray.remove()` não encerra nada |
| Windows reinicia | ambos morrem; recuperação por lease no próximo start | |

**Decisão para crash da App: o Worker termina o job corrente e encerra.** Não continua indefinidamente.

Justificativa de produto, não de implementação: o Worker existe para servir a App. Sem ela não há UI
para mostrar progresso, não há quem responda a cancelamento e não há supervisão — um Worker vivo
sozinho é um processo que consome CPU e mexe em arquivos do usuário sem nada por cima. Pior, quando o
usuário reabrir o Nimbus, a App iniciaria um **segundo** Worker, e a exclusão por lease não impede
dois processos mexendo no mesmo disco por caminhos diferentes.

Terminar o job corrente (em vez de morrer na hora) evita deixar arquivo pela metade: é o mesmo
graceful period do shutdown ordenado, com teto de tempo.

**Mecanismo**: `ProcessHandle.of(pidDaApp).onExit()` no Worker — não polling. Ao disparar, o Worker
entra no mesmo caminho de shutdown ordenado.

---

## 3. Supervisão do processo: `ProcessBuilder` direto, sem `cmd /c start`

**A preocupação levantada está correta, e a primeira versão estava errada.**

`cmd /c start "" /belownormal java ...` faz o `cmd` criar um processo **neto**: o `Process` que o Java
retém é o do `cmd`, que morre em seguida. Perde-se PID real, `onExit()` deixa de significar "o Worker
morreu", `destroy()` não alcança o Worker e órfão vira o caso normal. Exatamente os sintomas que o
`nimbus-update.cmd` produziu hoje, com o `/wait` esperando o processo errado.

**Solução:** `ProcessBuilder` direto sobre o executável Java, retendo o `Process`.

```
new ProcessBuilder(javaBin, "-Xmx<worker>", "-jar", jar, "--spring.profiles.active=worker",
                   "--nimbus.app-pid=" + ProcessHandle.current().pid())
```

Para **prioridade abaixo do normal**, sem intermediário: aplicar depois do start, com
`wmic process where processid=<pid> CALL setpriority 16384` ou `powershell -Command
"(Get-Process -Id <pid>).PriorityClass='BelowNormal'"` — um processo auxiliar de vida curta que
**não é o pai do Worker**. O `Process` retido segue sendo o Worker.

Se nem isso for desejável, a alternativa é o Worker **rebaixar a si mesmo** logo no start (o `NICE`
não existe na API do Java, mas o mesmo `Get-Process` sobre `ProcessHandle.current().pid()` resolve, e
aí o auxiliar morre dentro do próprio Worker).

**Regra:** supervisão correta tem precedência sobre prioridade. Se as duas conflitarem, prioridade sai.

---

## 4. Topologia do Worker e as três concorrências

**Uma JVM Worker.** Não múltiplos processos worker — não há ganho num app local e a supervisão dobra.

As três coisas que a primeira versão misturava, agora separadas por dono:

| Nível | O que controla | Quem controla | Config |
| --- | --- | --- | --- |
| **1. Entre Executions** | quantas `Execution` estão RUNNING ao mesmo tempo, e quais podem coexistir | **`ExecutionDispatcher`** (novo, no Worker): limite global + limites por categoria + exclusão de caminho | novo: `worker.max-concurrent`, limites por categoria |
| **2. Dentro de uma Execution** | quantos arquivos em paralelo dentro de um job | **`ProcessingCoordinator`** (o de hoje, sem mudança de papel) | `processing.workers`, `queue-capacity` |
| **3. Processos externos** | quantos ffmpeg/ffprobe coexistem, somando *todos* os jobs | **`ExternalToolGate`** (o de hoje), ortogonal aos dois | `ffmpeg-*-limit`, `ffprobe-video-limit` |

O dispatcher mantém **N jobs ativos** (default 2–3), cada um com sua thread de execução; um job pesado
usa o `ProcessingCoordinator` por dentro; o gate corta ffmpeg globalmente. Nenhum dos três substitui o
outro.

---

## 5. Claim, lease e renovação

### 5.1 O claim é uma transação curta

```
BEGIN
  SELECT id, execution_type, request_payload
    FROM execution
   WHERE status = 'PENDING'
     AND available_at <= now()
     AND <elegível: categoria e caminho livres — §10>
   ORDER BY priority DESC, id
   FOR UPDATE SKIP LOCKED
   LIMIT 1;

  UPDATE execution
     SET status = 'RUNNING', claimed_by = :workerId,
         claimed_at = now(), lease_until = now() + interval '2 minutes',
         started_at = coalesce(started_at, now())
   WHERE id = :id;
COMMIT
```

**Confirmação explícita: o row lock vive só dentro dessa transação — milissegundos.** O job executa
**depois** do commit, sem transação aberta. A posse durante a execução é expressa por
`claimed_by` + `lease_until`, dados comuns, não por lock de banco.

### 5.2 Renovação: um renovador dedicado, não a thread de trabalho

**A preocupação está certa.** A thread que executa pode ficar bloqueada em `waitFor()` de um ffmpeg ou
numa leitura de disco lenta; se ela fosse a responsável pelo heartbeat, um job legítimo perderia o
lease e seria roubado.

**Um único `ScheduledExecutorService` no Worker**, com uma thread, renova **todos** os leases ativos:

```
a cada 30 s:
  UPDATE execution
     SET lease_until = now() + interval '2 minutes'
   WHERE claimed_by = :workerId AND status = 'RUNNING' AND id = ANY(:idsAtivos)
```

Um `UPDATE` para os três jobs do exemplo (100, 101, 102), não três. O conjunto de ids ativos é um
`Set` concorrente que o dispatcher alimenta. Se o renovador morrer, todos os leases expiram — que é o
comportamento correto, porque significa que o Worker está doente.

### 5.3 Lease **não** é duração máxima

**Confirmado, e a primeira versão estava conceitualmente errada** ao dizer que o `PHASH_BACKLOG`
processa "até esvaziar ou até o lease expirar".

`lease_until` significa **"este Worker continua sendo dono"** e nada mais. Enquanto o renovador
estiver vivo, um job pode durar horas legitimamente.

Se um dia for preciso limitar duração ou dar fairness, isso é **outro conceito** (`max_duration` ou um
teto de itens por passagem) — nunca sobrecarregar `lease_until`. Para o backlog de pHash, o correto é
um **teto de itens por execução** (ele já processa "os próximos N"), que termina o job e enfileira
outro se sobrar trabalho.

---

## 6. Contadores: `claim_count` monotônico, sem compensação

**Correção da revisão anterior.** A proposta de incrementar no claim e *decrementar* no shutdown
ordenado estava errada por dois motivos concretos:

- um contador que anda para trás não significa mais o que o nome diz;
- há uma janela real de crash — `claim_count++` → shutdown começa → Worker morre antes do
  decremento — em que a semântica passa a depender do ponto exato da queda.

**Decisão: um único contador, `claim_count`, monotônico, jamais compensado.** Ele incrementa
**somente depois de os advisory locks terem sido adquiridos**, imediatamente antes de a execução
começar de verdade.

Significado exato:

> quantas tentativas efetivas de execução chegaram a adquirir os recursos necessários e começaram.

Disputa de lock, portanto, **não conta** — e com isso desaparece a única exceção que ainda tornava o
contador não-monotônico. A finalidade é uma só — **impedir poison job** — e nada de
`failure_count`/`retry_count`/`crash_count` separados: seriam três contadores para uma pergunta.

| Evento | `claim_count` | Efeito |
| --- | --- | --- |
| Reserva (`PENDING → RUNNING`) | **inalterado** | ainda não há posse dos caminhos |
| Advisory lock **não** obtido | **inalterado** | volta a `PENDING` com `available_at` + jitter |
| Advisory lock obtido, antes de executar | **+1** | **único ponto de incremento** |
| Falha tratada, com retry (§13) | inalterado | volta a `PENDING`; o próximo início conta |
| Falha sem retry | inalterado | terminal `ERROR` |
| Cancelamento do usuário | inalterado | terminal `CANCELLED` |
| Shutdown administrativo | inalterado | `INTERRUPTED`; sem compensação, sem decremento |
| Crash do Worker / kill externo | inalterado | já contou antes de começar; recuperação por lease |
| PostgreSQL indisponível | inalterado | idem |

O shutdown administrativo **não gasta crédito e não devolve nada**: termina em `INTERRUPTED`, que é
terminal e não volta à fila sozinho. Quem reprocessa é o usuário (botão *Reprocessar*), criando uma
tentativa nova que conta normalmente. Assim o contador nunca precisa de compensação.

### Poison job: como o ciclo é cortado

```
Worker inicia X → claim_count = 1 → OOM
Worker reinicia → lease de X vencido → X volta a PENDING
Worker inicia X → claim_count = 2 → OOM
Worker inicia X → claim_count = 3 → OOM
Worker reinicia → X tem claim_count = 3 → NÃO é elegível
              → recuperação marca ERROR com mensagem "falhou 3 vezes seguidas"
```

O corte aparece em **dois** pontos, e ambos são necessários (§9.4): no predicado da reserva
(`claim_count < MAX_CLAIMS`, que evita reservar o que já está esgotado) e — decisivamente — no
`UPDATE` condicional do próprio incremento, que é o que garante o limite mesmo se a leitura da reserva
tiver ficado obsoleta em milissegundos. Nenhum dos dois é checagem em memória que um crash poderia
pular.

Distinção entre causas, sem precisar diagnosticar cada crash:

| Causa | Como se manifesta | Consome crédito? |
| --- | --- | --- |
| Exception tratada | o job escreve `ERROR`/`PENDING` conforme §13 | conforme a política de retry |
| Crash da JVM / kill externo | lease vence, ninguém escreveu nada | sim (o claim já contou) |
| Shutdown ordenado | `INTERRUPTED` escrito antes de sair | não volta à fila |
| PostgreSQL perdido | lease vence | sim |

Os três últimos são indistinguíveis do ponto de vista do banco — e é justamente por isso que o
contador vive no claim: ele é o único ponto que registra a tentativa **antes** de qualquer coisa poder
dar errado.

---

## 7. `request_payload`: contrato, não bolsa de dados

Levantamento do que cada tipo realmente precisa:

| ExecutionType | Argumentos hoje | Já persistido? | Precisa no payload |
| --- | --- | --- | --- |
| `INVENTORY` | source, recursive, limit | `sourcePath`, `recursive` em `Execution` | **nada** |
| `ORGANIZATION` | source, target, layout, limit, allowConflicts, overwrite, subdivision, minConfidence, fallback, dryRun | `sourcePath`, `targetPath`, `executeFlag`, `recursive` | layout, limit, conflitos, overwrite, 3 de localização |
| `UNDO` | id da execução original | — | id da execução original |
| `CONVERSION` | ids públicos, perfil/opções | pasta em `sourcePath` | ids, perfil de qualidade |
| `DEDUP_DELETE` | ids dos duplicados | — | ids |
| `QUARANTINE_*` | política/retenção | — | política |
| `RECONCILE` | source | `sourcePath` | **nada** |
| `PHASH_BACKLOG`, `VIDEO_FINGERPRINT_BACKLOG` | tamanho do lote | — | **nada** (o lote é config) |
| `METADATA_REBUILD` | source, campos, cutoff, dateSource, limit | `sourcePath` | campos, cutoff, dateSource, limit |
| `LOCATION_REBUILD`, `GEO_DATASET_UPDATE`, `THUMBNAIL_REBUILD` | escopo | `sourcePath` quando houver | escopo, quando existir |

**Cinco dos treze tipos não precisam de payload nenhum** — reconstroem-se de `Execution`. Nada de
duplicar `sourcePath` no JSON.

Forma: **um record por tipo**, em `<domínio>/application/dto`, serializado pelo Jackson com
`schemaVersion` explícito no envelope:

```json
{ "schemaVersion": 1, "type": "ORGANIZATION", "layout": "YEAR_MONTH", "limit": 1000, ... }
```

Regras que decorrem de uma `Execution` PENDING poder ser consumida pela versão seguinte:

- campo novo entra **opcional com default** — nunca obrigatório;
- campo removido é **ignorado** na leitura (`@JsonIgnoreProperties(ignoreUnknown = true)`);
- mudança incompatível exige `schemaVersion` novo **e** a migration converte os PENDING existentes, ou
  os marca `INTERRUPTED` com mensagem clara. Nunca desserialização silenciosa e errada.
- payload que não desserializa → job vai para `ERROR` com mensagem, jamais é adivinhado.

Nada de `Map<String, Object>`.

---

## 8. Spring Batch: **REMOVER** — decisão binária

Levantamento do uso real (`InventoryJobConfig`, `InventoryFileItemReader`, `InventoryItemProcessor`,
`InventoryItemWriter`, `InventoryJobExecutionListener`, `InventoryBatchLauncherService`):

| Capacidade | Usada hoje? | Necessária? | Quem fornece no modelo novo | O que se perde |
| --- | --- | --- | --- | --- |
| `JobRepository`/`JobInstance`/`JobExecution` | sim, obrigatório pelo framework | **não** — quem registra a execução para o usuário é `Execution` | `Execution` | nada; hoje há **duas** fontes de verdade |
| `StepExecution` | sim, implicitamente | não | `ExecutionStep` (já existe) | nada |
| `ExecutionContext` | **aberto e atualizado, mas não guarda cursor** | não | — | nada: o reader não persiste posição |
| **Restart/checkpoint** | **não** — `spring.batch.job.enabled=false`, e nunca há restart de `JobInstance` | não | reexecução idempotente | nada, porque não existe hoje |
| Chunk transaction | sim, chunk de N | **sim** | `ProcessingCoordinator` + transação no writer, que já é código do projeto | precisa ser explicitada — é o único ponto real |
| `ItemReader` | sim, `ItemStreamReader` que anda a árvore preguiçosamente | sim | o mesmo walker, sem interface do Batch | nada; o `FileScanner` já existe |
| `ItemProcessor`/`ItemWriter` | sim | sim | classes do projeto, chamadas direto | nada |
| skip / retry / faultTolerant | **não configurado** | não | política de retry por categoria (§13) | nada |
| Listeners | sim, um: traduz o desfecho para `Execution` | sim | o próprio job faz isso | nada |
| Partitioning | **não** | não | — | nada |

**A evidência decisiva: o único recurso que justificaria Spring Batch — restart com checkpoint — não
está em uso.** O reader abre um `ExecutionContext` mas não grava cursor; o `StartupExecutionRecoveryListener`
marca tudo como `INTERRUPTED` justamente porque *"no cursor is persisted"*. Paga-se o preço do
framework (tabelas, `JobRepository`, uma segunda noção de execução) sem receber o benefício.

Some ainda que o inventário é **idempotente** — reexecutar uma passagem após crash é aceitável e
simples.

**Sai:**

- dependência `spring-boot-starter-batch` do `pom.xml`;
- `InventoryJobConfig`, `InventoryFileItemReader`, `InventoryItemProcessor` e `InventoryItemWriter`
  perdem as interfaces do Batch (a lógica permanece, como classes do projeto);
- `InventoryJobExecutionListener`, `InventoryBatchLauncherService`, `InventoryBatchAsyncRunner`,
  `InventoryItemWriterParameters` (reavaliar);
- `spring.batch.jdbc.initialize-schema` e `spring.batch.job.enabled` do `application.properties`;
- as tabelas `BATCH_*` da migration inicial — **removidas por migration nova** (breaking permitido);
- testes que exercitam o job/step do Batch.

---

## 9. `OperationLockService`: exclusão por caminho persistente, e o Explorer continua síncrono

Matriz dos onze usos:

| Operação | Onde roda hoje | Natureza | Duração | Síncrona p/ usuário? | Destino |
| --- | --- | --- | --- | --- | --- |
| `InventoryFileItemReader` | pool async | lê árvore | minutos | não | **Worker** (job) |
| `OrganizationExecutor` | pool async | **move** | minutos–horas | não | **Worker** (job) |
| `OrganizationService` (preview) | pool async | lê | segundos–min | não | **Worker** (job) |
| `OrganizationUndoService` | pool async | **move** | minutos | não | **Worker** (job) |
| `OrganizationReconcileService` | timer | **move/corrige** | segundos | não | **Worker** (job) |
| `VideoConversionService` | pool async | **escreve+move** | horas | não | **Worker** (job) |
| `DuplicateDeletionService` | pool async | **move p/ quarentena** | segundos–min | não | **Worker** (job) |
| `QuarantinePurgeService` (×2) | timer/ação | **apaga** | segundos | não | **Worker** (job) |
| **`ExplorerRenameService`** | **thread HTTP** | **rename** | **milissegundos** | **sim** | **App**, com lock persistente |
| **`ExplorerDeletionService`** (×2) | **thread HTTP** | **move p/ lixeira** | **milissegundos** | **sim** | **App**, com lock persistente |

**O Explorer continua síncrono.** Degradar "clicou renomear → renomeou" para "vira job, fica PENDING,
espera worker" seria trocar uma operação de milissegundos por uma de segundos, e a única razão seria
conveniência arquitetural. Isso é inaceitável para UX e desnecessário.

### Mecanismo: **advisory lock de sessão do PostgreSQL, por cadeia de caminhos**

Comparação das alternativas:

| Alternativa | Custo | Correção | Crash recovery | Veredicto |
| --- | --- | --- | --- | --- |
| Tabela de locks com linhas por caminho | INSERT/DELETE por operação; **milhares durante inventário se por arquivo** | boa se a granularidade for certa | precisa de TTL e limpeza | **rejeitada**: exige mais um mecanismo de expiração |
| `SELECT ... FOR UPDATE` sobre linha de recurso | zero escrita | **exige transação aberta durante a operação** | — | **rejeitada** por isso |
| **Advisory lock (`pg_try_advisory_lock`)** | **zero linha, zero escrita** | exclusão real entre processos | **liberado automaticamente ao cair a conexão** | **escolhida** |
| Coordenação só pela fila | simples | não cobre o Explorer, que não passa pela fila | — | insuficiente |

`OperationLockService` **permanece como classe e como API** (`acquire`, `acquireWithin`, `isBusy`,
`OperationLock` `AutoCloseable`), com o miolo trocado de `synchronized` para advisory lock. Os onze
chamadores não mudam. Isso preserva também a distinção que já existe entre "usuário espera" e
"agendado desiste".

### 9.1 Normalização do caminho — a parte perigosa

O PostgreSQL só conhece números. **Toda a semântica de hierarquia é do Nimbus**, e o risco real não é
colisão: é **o mesmo caminho gerar duas chaves diferentes**, o que apaga a exclusão em silêncio.

Normalização canônica, nesta ordem exata, num único método (`OperationPathKey.canonical`):

| Passo | Regra | Exemplo |
| --- | --- | --- |
| 1 | `Path.toAbsolutePath().normalize()` — resolve `.` e `..` | `D:\a\..\b` → `D:\b` |
| 2 | `toRealPath()` **quando o caminho existe**; se não existe, sobe até o ancestral existente, aplica `toRealPath()` nele e reanexa o resto | resolve junction/symlink e o *case* real do disco |
| 3 | separador único `\` | `D:/fotos` → `D:\fotos` |
| 4 | remove separador final, exceto na raiz de unidade | `D:\fotos\` → `D:\fotos`; `D:\` fica |
| 5 | `toLowerCase(Locale.ROOT)` — Windows é case-insensitive | `D:\Fotos` → `d:\fotos` |
| 6 | letra de unidade minúscula (consequência do passo 5) | `D:` → `d:` |
| 7 | UNC preservado com os dois separadores iniciais | `\\srv\share\x` → `\\srv\share\x` |

Sobre o passo 2: o projeto **não segue links para processar** (`PhysicalFilePolicy`, nunca
`FOLLOW_LINKS`), mas para *lock* o alvo real importa — uma junction e seu destino são o mesmo arquivo
físico, e travar só o nome pelo qual se chegou deixaria a porta aberta pelo outro. `toRealPath()` é a
resposta do próprio sistema de arquivos para "qual é o nome verdadeiro", e cobre junctions, symlinks e
reparse points de uma vez. Falhando (volume ausente), cai para o passo 1 — pior é abortar a operação.

Alias que `toRealPath()` **não** resolve — drive mapeado (`Z:` apontando para `\\srv\share`) e `subst`
— fica registrado como limitação conhecida: são o mesmo arquivo com caminhos legitimamente distintos,
e nenhuma normalização local os une. O produto trabalha sobre a biblioteca configurada, que é um
caminho só.

### 9.2 Da string à chave de 64 bits

**Nada de `String.hashCode()`** — não é estável entre versões de JVM por contrato e tem colisão
trivial de construir.

```
key = primeiros 8 bytes de SHA-256(canonical em UTF-8), lidos como long big-endian
```

SHA-256 é estável entre JVMs, versões e processos, e já é usado no projeto (`SecureFileMove`).
Trunca-se para 64 bits porque é o que `pg_advisory_lock(bigint)` aceita.

**Consequência de colisão — confirmado: falso conflito, nunca perda de exclusão.** Dois caminhos com
a mesma chave disputam o mesmo lock; um espera o outro. Perde-se paralelismo, não correção. Com 64
bits e algumas centenas de chaves vivas, a probabilidade é desprezível, e a falha é do lado seguro.

O risco que importa é o oposto — mesmo caminho, chaves diferentes — e é onde a cobertura se concentra:
uma tabela de casos (barra, caixa, `..`, trailing, UNC, junction, unidade minúscula/maiúscula, nome
acentuado, caminho > 260) afirmando que **todos colapsam na mesma chave**, e que App e Worker
produzem a chave idêntica para a mesma entrada.

### 9.3 Prefixos e ordem canônica de aquisição

Uma operação sobre `d:\fotos\2008\a.jpg` adquire as chaves de:

```
d:\                d:\fotos           d:\fotos\2008      d:\fotos\2008\a.jpg
```

— a cadeia completa da raiz do volume até o alvo. Assim um inventário de `d:\fotos` conflita com um
rename em `d:\fotos\2008`, porque ambos passam por `d:\fotos`. A profundidade é a de um caminho de
biblioteca, dígitos de nada.

**Ordem canônica global, obrigatória.** Todas as chaves de uma operação — inclusive as de *origem* e
*destino* de um move, e de volumes diferentes — vão para um **`TreeSet<Long>` único** e são adquiridas
em **ordem crescente do valor numérico da chave**. Como a ordem depende só da chave, e a chave só do
caminho, duas operações quaisquer sobre o mesmo conjunto pedem na mesma sequência.

Isso elimina o ciclo do exemplo (A pega X→Y enquanto B pega Y→X) **por construção**, sem depender de
timeout: com ordem total sobre os recursos, deadlock por espera circular não pode se formar. O
`acquireWithin` continua existindo para o caso de *espera longa*, não como rede contra deadlock.

Move entre unidades diferentes é apenas mais chaves no mesmo `TreeSet` — nada de tratamento especial.
UNC idem: a raiz da cadeia é `\\servidor\share`.

**Falha parcial**: se uma chave da cadeia não vier, as já adquiridas são liberadas em ordem inversa e a
operação inteira falha. Nada de segurar metade.

### 9.3.1 Hierarquia é intenção, não exclusividade em cadeia — correção

**Defeito encontrado durante a implementação do passo 3, com evidência de teste.** A §9.3 dizia
"a operação toma os locks dos prefixos até a raiz" sem dizer *em que modo*. Tomados todos em modo
**exclusivo**, duas operações em árvores irmãs se excluem, porque compartilham ancestrais — e no
limite a raiz do volume vira um mutex global: qualquer operação em `D:\` bloquearia qualquer outra
em `D:\`.

Isso não é teoria: o teste `grantsTwoPathsThatDoNotContainEachOther` falhou exatamente assim, com
`…otos` recusando `…ideos`.

**Protocolo final — locking hierárquico com intenção:**

| Nível | Modo | Comando |
| --- | --- | --- |
| O escopo que a operação protege | **EXCLUSIVE** | `pg_try_advisory_lock` |
| Cada ancestral do escopo, até a raiz | **SHARED** | `pg_try_advisory_lock_shared` |

Shared não conflita com shared, então descendentes independentes coexistem; shared conflita com
exclusive, então uma operação sobre o ancestral colide com todos os descendentes e vice-versa. É o
padrão clássico de *intent locks*, e o PostgreSQL o oferece pronto.

**Matriz de conflito, cada linha afirmada por um teste de integração:**

| # | Operação A | Operação B | Resultado | Por quê |
| --- | --- | --- | --- | --- |
| A | rename `D:\Fotos.jpg` | rename `D:\Fotos.jpg` | **coexistem** | chaves exclusivas distintas; `D:\Fotos` shared dos dois lados |
| B | rename `D:\Fotos.jpg` | operação sobre `D:\Fotos` | **conflito** | shared em `D:\Fotos` × exclusive em `D:\Fotos` |
| C | operação sobre `D:\Fotos` | operação sobre `D:\Videos` | **coexistem** | só compartilham `D:\`, em shared |
| D | `D:\Fotos5` | `D:\Fotos6` | **coexistem** | nenhuma reivindica `D:\Fotos` |
| E | `D:\Fotos` | `D:\Fotos6` | **conflito** | exclusive × shared no ancestral |
| F | move `D:\Fotos.jpg` → `D:\Videos.jpg` | operação sobre `D:\Videos` | **conflito** | pelo destino, não pela origem |
| G | move `D:\Fotos.jpg` → `E:\Backup.jpg` | qualquer coisa em `D:\` ou `E:\` fora dessas árvores | **coexistem** | nenhum volume fica exclusivo |

**Regra canônica de granularidade:** o `EXCLUSIVE` protege **o menor escopo que precisa permanecer
estável durante a operação** — nem mais, nem menos. Disso decorre tudo o resto:

- operação sobre um arquivo → `EXCLUSIVE` **naquele arquivo**;
- operação sobre uma pasta ou subárvore → `EXCLUSIVE` **na raiz dela**;
- operação com origem e destino → `EXCLUSIVE` nos **menores escopos efetivamente envolvidos** de cada
  lado;
- ancestrais desses escopos → `SHARED`, sempre;
- a pasta-mãe **não** é promovida a `EXCLUSIVE` só porque o arquivo mora nela, e a raiz da biblioteca
  ou do volume **nunca** é promovida.

O que isso *não* significa: não é "um lock por arquivo tocado". Um inventário de `D:\Fotos` reivindica
`D:\Fotos` porque a subárvore inteira é o que precisa ficar estável — e varrer 150 mil arquivos não
produz 150 mil locks. Já um rename reivindica só o arquivo, porque só ele precisa ficar estável.

**Source + target**: o conjunto completo é calculado **antes** de qualquer aquisição — as duas
hierarquias são montadas, deduplicadas e ordenadas. Se a mesma chave aparece shared por uma e
exclusive por outra (um dos caminhos é ancestral do outro), **vence exclusive**, que é a reivindicação
mais forte.

**Deadlock com dois modos**: a ordem continua sendo a numérica da chave, e continua suficiente —
`PathLockKey` compara **só pela chave**, nunca pelo modo, de forma que todos os participantes (App,
Worker, Explorer, jobs com origem e destino) pedem na mesma sequência. **Não existe promoção tardia**
de shared para exclusive: o modo final de cada chave é decidido antes da primeira aquisição, o que
elimina a classe de deadlock em que duas sessões seguram shared e ambas tentam subir para exclusive.

**Verificação de posse por modo**: `stillHolds` não conta N locks — confere que cada chave exclusiva
continua em `ExclusiveLock` e cada compartilhada em `ShareLock`, sob o `pg_backend_pid()` da própria
sessão. Uma reivindicação exclusiva que reaparecesse como compartilhada não é a mesma reivindicação.

**Leitura também tranca.** O critério não é "a operação escreve?", e sim "que escopo precisa ficar
estável enquanto ela dura?". Inventário, hash e metadata só leem, e ainda assim tomam o escopo
exclusivamente: um rename no meio da varredura produz catálogo apontando para caminho inexistente.

### 9.4 Claim e lock são duas etapas — e o SELECT não sabe de caminho

**Correção da revisão anterior.** O `WHERE ... <caminho livre>` da §5.1 era ficção: "caminho livre" é
estado de *outra conexão*, invisível para o `SELECT` da fila. O fluxo real:

```
1. dispatcher confere vaga (global e por categoria)          — em memória, no Worker
2. RESERVA — transação curta:
     BEGIN
       SELECT ... WHERE status='PENDING' AND available_at<=now()
                    AND claim_count < :maxClaims
                  ORDER BY <aging> FOR UPDATE SKIP LOCKED LIMIT 1
       UPDATE → status='RUNNING', claimed_by, claimed_at, lease_until
                                              -- claim_count NÃO muda aqui
     COMMIT
3. obtém a conexão dedicada de lock desta Execution
4. pg_try_advisory_lock em cada chave, em ordem canônica (§9.3)
5. NÃO conseguiu → libera as parciais, fecha a conexão, devolve atomicamente:
                   status='PENDING', claimed_by=NULL, claimed_at=NULL, lease_until=NULL,
                   available_at = now() + jitter (5–15 s)
                   -- claim_count permanece inalterado: disputa de lock não é tentativa
6. CONSEGUIU → contabiliza a tentativa, com guarda atômica:
     UPDATE execution
        SET claim_count = claim_count + 1
      WHERE id = :id AND claimed_by = :workerId
        AND status = 'RUNNING' AND claim_count < :maxClaims
   → exatamente 1 linha alterada?  não → §9.4.1
7. só então o executor de domínio é chamado
```

O `SELECT` da fila filtra **só** o que ele sabe: status, `available_at`, `claim_count`, prioridade.
Caminho é resolvido no passo 4, onde a informação de fato existe.

A guarda do passo 6 não é redundante com a do passo 2: entre uma e outra passaram-se a aquisição dos
locks e possivelmente uma espera. **Não se depende de uma leitura feita milissegundos antes** — o
próprio `UPDATE` reafirma o limite, a posse (`claimed_by`) e o estado, e a contagem de linhas
afetadas é a resposta.

### 9.4.1 Quando o passo 6 não afeta exatamente uma linha

Três causas possíveis, e **nenhuma delas executa nada**:

| Zero linhas porque | Ação |
| --- | --- |
| `claim_count` já atingiu o máximo | libera os locks, marca `ERROR` — poison job esgotado, com mensagem |
| `claimed_by`/`status` mudaram (a posse foi tomada por uma recuperação) | libera os locks, abandona silenciosamente; a Execution é de outro |
| a própria escrita falhou (PostgreSQL caiu) | libera/abandona os locks, **não inicia o job**; o lease vence e a recuperação resolve depois |

**A janela entre 4 e 6** — locks adquiridos, incremento ainda não persistido, Worker morre — é
inofensiva **por construção**: nesse ponto nenhuma linha de lógica de domínio foi executada. Os locks
morrem com a conexão, a Execution fica `RUNNING` com lease que vencerá, e a recuperação a devolve à
fila com o `claim_count` que ela tinha. Nada foi processado sem ser contado, e nada foi contado sem
ser processado.

**A janela entre 2 e 4** (a Execution está `RUNNING` mas ainda sem lock) é igualmente inofensiva:
`RUNNING` não autoriza tocar em arquivo nenhum. **Quem autoriza é o advisory lock mais o incremento
persistido**, nessa ordem, e nenhum caminho de código mexe no filesystem antes do passo 7. É
invariante (nº 16), não coincidência.

### 9.5 Uma conexão de lock por Execution ativa

Confirmado ponto a ponto:

- **cada Execution ativa tem a sua própria conexão** — com 3 jobs, 3 conexões de lock;
- ela **nasce com o `OperationLock` e morre com ele** (`AutoCloseable` fecha a conexão);
- **nunca é compartilhada**: advisory lock de sessão é propriedade *da sessão*, então dois jobs na
  mesma conexão poderiam liberar o lock um do outro — exatamente o cenário a evitar;
- **perder a conexão invalida a posse imediatamente**, porque o PostgreSQL libera os locks daquela
  sessão ao fechá-la;
- e o job **detecta** essa perda pelo mecanismo da §9.6.

**Fora do Hikari, deliberadamente.** Uma conexão do pool do Hibernate presa por horas roubaria uma
vaga de um pool dimensionado para requisições curtas, e um `evict` do pool derrubaria o lock sem
ninguém saber. São `DriverManager.getConnection` sobre as coordenadas **do pool ativo**
(`HikariDataSource.getJdbcUrl()`) e não sobre a URL configurada - o cluster embarcado escolhe a porta
em runtime e um teste liga num contêiner. Contadas num limite próprio
(`worker.max-concurrent` + margem para o Explorer), com `try-with-resources` obrigatório. O
`max_connections` do cluster embarcado precisa contemplar essa margem — é uma linha de configuração,
verificada no start.

### 9.5.1 Reentrância na mesma thread — descoberto na implementação

O lock em memória ignorava conflitos vindos da **própria thread** (`ownerThreadId`), e o código
depende disso: `OrganizationService.acquire(source, target)` chama `OrganizationExecutor`, que
adquire **os mesmos caminhos** de novo. Trocado o miolo sem essa propriedade, a organização passou a
travar contra si mesma - `InventoryOrganizationReinventoryTest` acusou com `moved() == 0`.

Restaurada da forma que o mecanismo novo já oferece: um `ThreadLocal<NestedLockSession>` guarda a
sessão que a thread está usando, e um acquire aninhado **reusa essa sessão**. Advisory locks são
reentrantes dentro de uma sessão, então o pedido interno simplesmente é concedido - não há caso
especial no código de conflito. A profundidade conta apenas para decidir quando a conexão volta: só
o fechamento mais externo a devolve.

Consequência para os testes: um conflito de verdade exige um **holder em outra thread**, que é o que
uma segunda operação (ou um segundo processo) realmente é.

### 9.6 Detectar que a conexão de lock morreu — mecanismo concreto

**Este era o buraco mais perigoso do documento.** O cenário:

```
PostgreSQL reinicia → todos os advisory locks somem
Worker segue movendo arquivos, sem saber
Explorer adquire o lock do mesmo caminho — e consegue
→ duas operações mutando os mesmos arquivos
```

Uma conexão TCP morta não se anuncia; sem ninguém tocá-la, o Worker demoraria minutos para perceber.

**Mecanismo: o renovador de lease (§5.2) valida também as conexões de lock, no mesmo tique de 30 s.**
Ele já existe, já é a thread que não bloqueia, e já roda na cadência certa. Em cada tique, para cada
Execution ativa, sobre a **própria conexão de lock daquele job**:

```sql
SELECT count(*) FROM pg_locks
 WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND objid = ANY(:chaves)
```

Não é `SELECT 1`: um `SELECT 1` prova que a conexão está viva, mas **não** que os locks ainda são
dela — e a diferença é exatamente o cenário do reinício, em que uma conexão reconectada por baixo
responderia `1` alegremente sem lock nenhum. A consulta acima só é satisfeita se **estas** chaves
ainda pertencem a **esta** sessão.

Contagem menor que a esperada, ou exceção na consulta → **a posse foi perdida**. Consequências, em
ordem:

1. o job é marcado internamente como *sem posse* (um `volatile boolean` no contexto da Execution);
2. **nenhuma mutação nova de filesystem começa** — a checagem entra no mesmo ponto que já verifica
   cancelamento, entre lotes, e também **imediatamente antes de cada `SecureFileMove`**;
3. a operação em curso (um move individual, que é atômico e verificado) termina;
4. o job aborta e vira `INTERRUPTED`, quando o banco voltar; se não voltar, o lease vence e a
   recuperação faz isso.

O intervalo entre a perda real e a detecção é de no máximo um tique. Para fechá-lo no ponto que
importa, a checagem imediatamente antes de cada `SecureFileMove` usa a flag já publicada pelo
renovador — custo zero, e nenhum arquivo é movido sob posse sabidamente perdida.

**Redução do intervalo**: `tcp_user_timeout` e `socket_timeout` no driver fazem a conexão morta
falhar rápido em vez de esperar o TCP desistir. É configuração, não mecanismo, e não substitui a
verificação acima.

---

## 10. `SelfWrittenPathRegistry` cruzando processos

Hoje: `Map<String, Instant>` com TTL de 5 min, **consumo único** (o primeiro evento casado apaga a
entrada) — desenhado depois de uma conversão disparar cinco varreduras completas de 145 mil arquivos.

Com o Worker escrevendo e a App observando, a memória não serve. **Solução: a mesma semântica, numa
tabela pequena.**

```
self_written_path (path_key PK, announced_at, execution_id)
```

### 10.1 O consumo único **não** é suficiente — o que o código mostra

A revisão pediu prova, e a investigação do watcher real encontrou um defeito concreto na proposta
anterior. Dois achados:

**(a) O watcher ignora o tipo do evento.** `FileNotifyInformationParser.parse` lê o
`FILE_NOTIFY_INFORMATION` e extrai **apenas o nome** — o campo `Action` não é interpretado em lugar
nenhum do projeto. Não existe distinção entre `FILE_ACTION_ADDED`, `MODIFIED`, `RENAMED_OLD_NAME` e
`RENAMED_NEW_NAME`: tudo vira `Path`. E o handle é aberto com
`FILE_NOTIFY_CHANGE_FILE_NAME | DIR_NAME | SIZE | LAST_WRITE`, ou seja **quatro** classes de
notificação para a mesma escrita.

**(b) A deduplicação existe, mas só dentro de um poll.** `RdcwChangeInterpreter.interpret` colapsa os
caminhos num `LinkedHashSet<Path>`. Então os N eventos de uma escrita — nome, tamanho, última
gravação — viram **uma** entrada… **desde que caiam no mesmo poll**.

É aí que o consumo único quebra. Uma conversão de vídeo escreve por minutos: `SIZE` e `LAST_WRITE`
chegam em polls sucessivos. O primeiro poll consome o anúncio, e **todos os polls seguintes veem o
mesmo caminho sem anúncio nenhum** — cada um disparando o inventário completo que o registry existe
para evitar. É exatamente o sintoma documentado no código ("cinco varreduras completas de um disco de
145 mil arquivos durante um lote de conversão"), que hoje não aparece só porque a escrita e o watcher
estão na mesma JVM e o `SecureFileMove` anuncia imediatamente antes de cada move.

### 10.2 Mecanismo final: anúncio ancorado na Execution, não consumido no primeiro match

```
self_written_path (path_key PK, execution_id, announced_at)
```

- **Anúncio**: quem escreve insere (`SecureFileMove` já anuncia os dois extremos; o
  `DefaultExplorerFileSystem` anuncia o seu). `ON CONFLICT DO UPDATE` renova `announced_at`.
- **Consulta, não consumo**: o watcher faz `SELECT` e suprime enquanto a entrada existir. **Sem
  `DELETE ... RETURNING`.** Assim os eventos repetidos da mesma escrita, em polls sucessivos, são
  todos suprimidos.
- **Remoção pelo fim da operação**: quando a Execution chega a estado terminal, suas entradas são
  apagadas após uma **margem de dois polls** (o suficiente para o rabo de eventos chegar). Para
  escritas da App fora de job (Explorer), `execution_id` é nulo e vale só o TTL.
- **TTL de segurança**: os 5 minutos atuais continuam como teto absoluto, varridos pelo
  `CatalogFilePurgeScheduler`. Nenhuma entrada pode silenciar um caminho para sempre — inclusive se o
  Worker morrer sem limpar.

**O que se perde, dito explicitamente:** uma alteração *externa* no mesmo arquivo, **enquanto o Nimbus
o está escrevendo**, é suprimida. O consumo único de hoje cobriria esse caso. É uma troca consciente:
o cenário exige o usuário editar exatamente o arquivo que está sendo convertido naquele instante, e o
`RECONCILE` posterior o alcança; do outro lado da balança está o inventário completo em rajada, que é
o defeito real e medido. Fora da janela da operação, nada muda: a supressão acaba com ela.

**Nada de "ignorar caminhos com job ativo"** — isso suprimiria a pasta inteira. Aqui a supressão
continua sendo **por caminho anunciado**, e só dele.

Volume: uma linha por arquivo *escrito* pelo Nimbus, não por arquivo lido. Um inventário de 150 mil
arquivos insere zero linhas.

---

## 10.3 Cancelamento: o que é durável e o que é efêmero

Registrado explicitamente para que nada passe a depender do lado errado:

| Estado | Onde vive | Natureza |
| --- | --- | --- |
| `cancel_requested` | coluna de `execution` | **a verdade**. Persistente, visível a qualquer processo, sobrevive a restart |
| `running` / `isLive` | `Set` em memória, no `ExecutionCancellationService` | **efêmero**, e só responde por esta JVM |
| resposta em cache | mapa, 500 ms | otimização de leitura; nunca uma fonte |

`isLive` existe por uma razão de transição e uma só: enquanto houver execução que roda **sem passar
por claim/lease**, ela segura lock sem segurar lease, e a recuperação a declararia órfã. Quando o
dispatcher assumir todas as execuções, o lease responde a mesma pergunta — e entre processos, que é o
que a memória nunca fez —, e `isLive` sai junto com os runners in-process.

**Nenhuma decisão futura pode passar a depender dele.** Quem precisa saber se uma execução está sendo
trabalhada pergunta ao lease; quem precisa saber se foi cancelada pergunta à coluna.

---

## 11. Máquina de estados: separar fila de fase

**A revisão confirma a suspeita: `status` faz duas funções hoje.** `STARTED`/`SCANNING_FILES`/
`PROCESSING_FILES` são **fases** de uma execução em andamento; `FINISHED`/`ERROR`/`CANCELLED`/
`INTERRUPTED`/`REJECTED` são **desfechos**. `ExecutionStatusNames.IN_PROGRESS` existe justamente para
agrupar as três primeiras — prova de que a distinção já é necessária e hoje é feita por um conjunto.

**Proposta (breaking, aprovado):**

- **`status`** vira o ciclo de vida da fila, e só ele:
  `PENDING → RUNNING → FINISHED | FINISHED_WITH_ERRORS | ERROR | CANCELLED | INTERRUPTED | REJECTED`
- **`phase`** (coluna nova, nullable) carrega a fase funcional: `SCANNING`, `PROCESSING`,
  `VERIFYING`, … usada pela tela de progresso.
- **`STARTED` desaparece** — era "começou", que agora é `RUNNING`.
- O claim considera `RUNNING` (e só ele) como em execução, o que elimina o conjunto
  `IN_PROGRESS_NAMES` para efeito de fila.

Ganhos concretos: `markInterruptedExecutions` deixa de depender de um conjunto de três nomes; a UI
para de comparar strings de fase para decidir se algo está rodando; e a fila tem um predicado único.

---

## 12. Prioridade e fairness

`priority DESC, id` sozinho **permite starvation** de manutenção agendada — num app pessoal, jobs
interativos chegam em rajada e a fila esvazia entre elas, mas a garantia não existe.

**Política mínima, sem scheduler sofisticado: envelhecimento.** A ordenação passa a ser

```
ORDER BY (priority + LEAST(EXTRACT(EPOCH FROM (now() - created_at)) / 3600, 5)) DESC, id
```

— cada hora de espera vale um ponto, até cinco. Uma manutenção parada há cinco horas alcança um job
interativo recém-chegado. É uma expressão, não um subsistema, e é testável com relógio fixo
(`ClockHolder` já existe).

**Consequência no plano de acesso, assumida conscientemente:** uma expressão que depende de `now()`
**não é ordenável por índice**. O planner vai fazer *seq scan* (ou *bitmap* pelo índice parcial) sobre
as linhas `PENDING` e ordenar em memória.

Isso é aceito porque a fila `PENDING` de um app pessoal tem dezenas de linhas — ordenar dezenas de
tuplas custa microssegundos, e o claim acontece alguns por minuto, não milhares por segundo. O índice
parcial `WHERE status = 'PENDING'` continua valendo: ele mantém o conjunto varrido pequeno mesmo com
um histórico de centenas de milhares de execuções terminais, que é o volume que de fato cresce.

Nada de prioridade materializada, coluna denormalizada ou job de recálculo para agradar o índice —
seria complexidade paga por um problema que este produto não tem. Se algum dia a fila `PENDING` passar
de alguns milhares, isso é medido e revisto; até lá, o simples é comprovadamente suficiente.

---

## 13. Deduplicação atômica e retry por causa

**A contradição apontada é real.** Um único índice sobre `status IN ('PENDING','RUNNING')` proíbe
`INVENTORY(X, RUNNING)` + `INVENTORY(X, PENDING)` — que é exatamente o que se quer permitir, porque é
o que substitui o `inventoryPending`: um inventário rodando e **um** pedido esperando a vez.

**DDL que representa a matriz exatamente — dois índices parciais independentes:**

```sql
CREATE UNIQUE INDEX ux_execution_pending ON execution (execution_type, dedup_key)
 WHERE status = 'PENDING' AND dedup_key IS NOT NULL;

CREATE UNIQUE INDEX ux_execution_running ON execution (execution_type, dedup_key)
 WHERE status = 'RUNNING' AND dedup_key IS NOT NULL;
```

Um índice diz "no máximo 1 esperando", o outro "no máximo 1 rodando", e **os dois juntos permitem
1 + 1**. `dedup_key` é o caminho canônico (§9.1) ou uma constante para tipos globais; **nulo desliga a
deduplicação**, que é como os tipos que aceitam pedidos repetidos entram na mesma DDL sem exceção.

| Tipo | `dedup_key` | ≤1 PENDING | ≤1 RUNNING | PENDING+RUNNING |
| --- | --- | --- | --- | --- |
| `INVENTORY`, `RECONCILE` | caminho canônico | sim | sim | **permitido** — substitui `inventoryPending` |
| `PHASH_BACKLOG`, `VIDEO_FINGERPRINT_BACKLOG` | constante do tipo | sim | sim | **permitido** — um lote rodando, o próximo enfileirado |
| `GEO_DATASET_UPDATE`, `THUMBNAIL_REBUILD`, `LOCATION_REBUILD` | constante do tipo | sim | sim | **permitido**, mesma razão |
| `METADATA_REBUILD` | caminho canônico | sim | sim | permitido |
| `CONVERSION`, `DEDUP_DELETE`, `ORGANIZATION`, `UNDO`, `QUARANTINE_*` | **nulo** | — | — | livre: são pedidos distintos do usuário |

A corrida vira violação de constraint, tratada como "já existe, nada a fazer" — nunca
SELECT-então-INSERT.

**Retry por categoria de causa, nunca `catch(Exception) → claim_count++`:**

| Causa | Retry | Estado final |
| --- | --- | --- |
| PostgreSQL indisponível | sim, com backoff | volta a `PENDING` |
| Lock não obtido | sim, imediato com jitter | `PENDING` |
| Timeout de processo externo | sim, uma vez | depois `ERROR` |
| ffmpeg com código de erro | **não** | `ERROR` — entrada ruim não melhora repetindo |
| Arquivo sumiu | **não** | conta como *skipped*, não como falha |
| Acesso negado | **não** | `ERROR` com mensagem acionável |
| Payload inválido | **não** | `ERROR` |
| Corrupção / integridade (`MoveIntegrityException`) | **não** | `ERROR` — é o caso que o `SecureFileMove` existe para não repetir |
| Crash do Worker | sim, até `claim_count` atingir o máximo | depois `ERROR`, como poison job esgotado |

---

## 14. Conversão: temporário, commit e a janela entre rename e banco

Evidência: `ConversionFilePlacement.place()` chama `secureFileMove.move(converted, target, false)` e
**verifica que o arquivo chegou** — o comentário registra o incidente em que *"the batch counted the
video as converted while the catalog pointed at a path with nothing behind it and the encode sat next
to it under the temporary name"*.

- **Dono do temporário**: o job. O nome passa a carregar o `publicId` da `Execution`
  (`<nome>.<publicId>.part`), o que hoje não acontece — é o que permite saber a quem pertence um
  resto.
- **Cancelamento**: o job apaga o próprio temporário antes de finalizar `CANCELLED`.
- **Crash do Worker**: os temporários ficam. A varredura de recuperação no start apaga os `.part`
  cujo `publicId` pertence a execução em estado terminal.
- **Crash entre o move e o `UPDATE` do banco**: é a janela irredutível. O arquivo está no destino e o
  catálogo não sabe. **Quem resolve já existe**: `OrganizationReconcileService` e o `Movement` —
  reconciliação compara filesystem e catálogo. A recuperação enfileira `RECONCILE` para a pasta da
  execução interrompida, em vez de tentar adivinhar.

---

## 15. PostgreSQL fora do ar durante mutação de arquivo

Este é o caso em que "o lease resolve" **não** basta: o filesystem mudou e o banco não registrou.

- **Advisory lock**: cai junto com a conexão, então a exclusão se perde — mas o Worker também perde a
  capacidade de continuar. **Regra: perder a conexão de lock aborta a operação em curso** no próximo
  ponto de checagem (o mesmo ponto que já verifica cancelamento).
- **Movimentos já feitos**: cada `Movement` é escrito na mesma transação que atualiza o catálogo
  (`OrganizationMovePersistence`, `applyUndoToDatabase` com `transactionTemplate`). O que se perde é
  o *último* movimento, não o histórico.
- **Reconciliação**: no retorno do banco, a execução aparece `RUNNING` com lease vencido → vira
  `INTERRUPTED`, e a recuperação enfileira `RECONCILE` da pasta. É o mecanismo que o produto já usa
  para "o disco e o catálogo divergiram".

Ou seja: a resposta não é evitar a janela (impossível sem transação distribuída), é **detectá-la e
reconciliar** com o que já existe.

---

## 16. Composição do Spring: App, Worker e ambos

| Subsistema | App | Worker |
| --- | --- | --- |
| Tomcat, MVC, Thymeleaf, sessão/auth | sim | **não** (`web-application-type=none`) |
| Bandeja (`ApplicationTray`, `TrayLifecycle`) | sim | não |
| `EmbeddedClusterService` (PostgreSQL) | sim | **não** |
| **Flyway** | sim | **não** (§17) |
| Watcher (`InventoryWatchService`) e os seis timers | sim | não |
| Supervisão do Worker | sim | não |
| Repositórios, entidades, `ClockHolder` | sim | sim |
| `ProcessingCoordinator`, `ExternalToolGate`, tools | não | **sim** |
| Serviços de domínio pesados (inventário, organização, conversão, dedup, metadata, geo, thumbnails) | **não** | sim |
| `OperationLockService` | sim (Explorer) | sim |
| Dispatcher/claim/renovador de lease | não | sim |

**Mecanismo: `@Profile("app")` / `@Profile("worker")` nas classes de configuração**, não em cada bean.
Profile é suficiente e é o vocabulário do Spring; `@ConditionalOn...` seria mais indireto sem ganho.
Regra prática: quem produz job ou fala com o usuário é `app`; quem consome job é `worker`; entidade e
repositório não levam profile.

---

## 16.1 Os três modos de execução

| Profile | Processo | Papel |
| --- | --- | --- |
| `app` | JVM principal | UI/API, controle, supervisão do banco, **produtor** da fila |
| `worker` | JVM secundária | **consumidor**: claim, lock, execução do domínio |
| `app-worker-combined` | JVM única | composição `app` + `worker`, para desenvolvimento e debug |

**`app-worker-combined` não é um terceiro modelo.** É um *profile group* do Spring
(`spring.profiles.group.app-worker-combined=app,worker`) — ativa os outros dois e não acrescenta
nada. O trabalho continua obrigatoriamente pelo mesmo caminho:

```
UI/App → Execution PENDING → PostgreSQL → ExecutionQueue → WorkerLoop → ExecutionDispatcher
       → reserve → OperationLock → countAttempt → ExecutionJobHandler → domínio
```

Não existe chamada direta App → runner, nem bypass de fila, claim, lease ou advisory lock, nem
dispatcher alternativo. A única diferença é a fronteira de processo.

**Ele não dá o isolamento que motivou o A8** — mesma JVM significa mesmo heap, mesmo GC, mesmo
escalonamento de threads. É para poder dar Debug uma vez e entrar de um controller num job handler no
mesmo depurador. Produção continua com App JVM + Worker JVM.

**Papel explícito, com default compatível.** `spring.profiles.default=app` torna a role da App
explícita no arquivo sem quebrar o instalado: MSI, bandeja e qualquer launcher existente iniciam o jar
sem argumento de profile e continuam recebendo a aplicação.

**Onde a diferença de papel mora**, e onde não mora:

- **Profile no componente** — é a forma normal: `@Profile(NimbusProfiles.APP)` no watcher, na bandeja,
  nos timers e no filter chain; `@Profile(NimbusProfiles.WORKER)` no dispatcher, no loop e no
  renovador.
- **Uma exceção, centralizada** — `EmbeddedDatabaseBootstrap` é `EnvironmentPostProcessor` e roda
  antes de existir contexto, então nenhum bean pode guardá-lo: ele pergunta ao ambiente se o papel
  `app` está ativo. É a única verificação de papel em código, e existe porque não há alternativa.
- **Argumento de quem inicia** — `spring.main.web-application-type=none` e `spring.flyway.enabled=false`
  são passados pelo supervisor ao iniciar o Worker, **não** postos em `application-worker.properties`:
  um grupo ativa seus membros depois de si mesmo, então o que estivesse ali valeria também para o
  combined e o deixaria sem tela e sem schema.

**Eclipse**: Run/Debug As → Java Application sobre `NimbusFileManagerApplication`, com o argumento de
programa `--spring.profiles.active=app-worker-combined`. Nada escondido no projeto.

---

## 17. Protocolo de startup e compatibilidade de schema

1. App sobe → garante PostgreSQL (já faz) → **Flyway migra** → contexto pronto.
2. Só então a App inicia o Worker, passando a **versão de schema esperada** como argumento.
3. O Worker sobe com `spring.flyway.enabled=false` e, **antes de fazer qualquer claim**, valida:
   `SELECT MAX(version) FROM flyway_schema_history` = versão esperada.
4. Divergente → o Worker **falha no start com mensagem explícita** e código de saída próprio; a App
   não o reinicia em laço (backoff com desistência) e mostra o erro.

Isso cobre o caso de alguém executar o modo worker à mão: sem App, sem migration, ele recusa a subir
em vez de operar sobre schema velho.

---

## 18. Shutdown ordenado, e por que ele não é cancelamento

Sequência no Worker ao receber shutdown (bandeja, update, uninstall ou morte da App):

1. **para de fazer claims** — PENDING continua PENDING, intocado;
2. sinaliza os jobs ativos com uma flag de *shutdown*, **distinta** de `cancel_requested`;
3. concede **período de graça** (30 s, configurável): o job encerra o lote corrente e escreve estado;
4. quem terminou nesse período → estado final normal;
5. quem não terminou → `INTERRUPTED`, **sem alterar `claim_count`** — não há compensação nem
   devolução de crédito (§6); o usuário reprocessa quando quiser, e essa tentativa conta normalmente;
6. processos externos filhos (ffmpeg) recebem `destroy()` e, no teto, `destroyForcibly()` — o padrão
   que os runners já usam;
7. fecha o `DataSource` (o advisory lock some com a conexão);
8. **só então** a App para o PostgreSQL.

**Cancelamento ≠ shutdown**: o primeiro é decisão do usuário sobre aquele trabalho e termina em
`CANCELLED`; o segundo é administrativo e termina em `INTERRUPTED`, que a UI já apresenta com a opção
*Reprocessar*.

---

## 19. Classificação dos runners: nem todo `@Async` vai para o Worker

A coluna de estado foi acrescentada depois da Fase 4: a classificação não mudou, o tempo verbal sim.

| Runner | Classificação | Destino | Estado | Motivo |
| --- | --- | --- | --- | --- |
| `InventoryBatchAsyncRunner` | processamento | **Worker** | **entregue** — `INVENTORY` | |
| `OrganizationAsyncRunner` | processamento | Worker | **entregue** — `ORGANIZATION`, `ORGANIZATION_PREVIEW`, `UNDO` | |
| `VideoConversionAsyncRunner` | processamento | Worker | **entregue** — `CONVERSION` | |
| `DuplicateDeletionAsyncRunner` | processamento | Worker | **entregue** — `DEDUP_DELETE` | |
| `PhotoSimilarityAsyncRunner`, `VideoSimilarityAsyncRunner` | processamento | Worker | **entregue** — `SIMILARITY_PHOTO`, `SIMILARITY_VIDEO` (4.2.6) | |
| `PhashBacklogAsyncRunner`, `VideoFingerprintBacklogAsyncRunner` | processamento | Worker | **entregue** — `FINGERPRINT_PHOTO`, `FINGERPRINT_VIDEO` (5.1) | |
| `MetadataRebuildAsyncRunner` | processamento | Worker | **entregue** — `METADATA_REBUILD` (5.2) | |
| `LocationRebuildAsyncRunner`, `GeoDatasetAsyncRunner` | processamento | Worker | **entregue** — `LOCATION_REBUILD`, `GEO_DATASET_UPDATE` (5.3) | |
| **`UpdateInstallAsyncRunner`** | **controle da aplicação** | **App** | **permanece** | baixa o instalador e **encerra a própria aplicação**. Um Worker subordinado não pode ser quem substitui a App: ele teria de matar seu supervisor e sobreviver ao próprio jar sendo trocado |
| **`ExternalToolInstallAsyncRunner`** | **controle** | **App** | **permanece** | instala o ffmpeg que o Worker usa; se o Worker dependesse dele para si mesmo, haveria dependência circular no primeiro uso |
| **`CatalogBackupAsyncRunner`** | **controle** | **App** | **permanece** | `pg_dump`/`pg_restore` contra o cluster que a App supervisiona; o restore **derruba conexões** — inclusive as do Worker. Quem coordena isso tem de ser o dono do cluster. O Worker é pausado durante o restore |

Portanto: **três dos catorze runners permanecem na App**, e isso é decisão de domínio, não exceção
técnica. Continua valendo depois da Fase 4, e o `CatalogBackupAsyncRunner` em particular não deve ser
migrado só para zerar a contagem de `@Async`: ele não muta a biblioteca, e o que ele coordena é o
cluster que a App supervisiona.

---

## 20. O que cada controle de recurso realmente faz

| Controle | O que **faz** | O que **não** faz |
| --- | --- | --- |
| `worker.max-concurrent` (dispatcher) | limita quantas Executions coexistem | não limita threads dentro delas |
| `processing.workers` | limita arquivos em paralelo dentro de um job | não limita processos externos |
| `ExternalToolGate` | limita processos ffmpeg/ffprobe **simultâneos** | **não limita o uso de CPU de cada ffmpeg** |
| Prioridade `BELOW_NORMAL` do processo | faz o escalonador do Windows preferir a App sob disputa | não impõe cota; com CPU ociosa o Worker usa tudo |
| `-XX:ActiveProcessorCount` | muda o que `Runtime.availableProcessors()` responde, e com isso o dimensionamento de pools e de bibliotecas **dentro da JVM** | **não é cota de CPU**; e **não alcança o ffmpeg**, que é processo externo |
| Heap separado (`-Xmx`) | isola pressão de GC e memória — o ganho mais real da separação | não isola CPU nem disco |

**Para limitar ffmpeg é preciso usar o próprio ffmpeg**: `-threads N` na linha de comando. Isso não
existe hoje nos runners e é a única forma correta de conter o consumo dele.

Honestidade: não há isolamento de disco. Com CPU e disco compartilhados, o que preserva a UI é o
conjunto — heap separado, prioridade, limites de concorrência e `-threads` no ffmpeg.

---

## 21. Vocabulário: `ExecutionQueue`, não `JobQueue`

`JobQueue` reintroduziria pelo nome o conceito que a decisão descarta. Os ports passam a ser:

- **`ExecutionQueue`** — `reserve`, `countAttempt`, `release`, `renewLeases`, `expiredLeases`.
  **Classe concreta `@Repository` em `execution/infrastructure/persistence`, sem interface**: o
  `AGENTS.md` está acima deste documento na hierarquia e diz que repositório JDBC custom é adapter
  concreto, e que uma interface com um implementador só para embrulhar o framework é cerimônia. O
  nome ubíquo permanece;
- **`ExecutionDispatcher`** (no Worker) — decide o que rodar agora, dentro dos limites;
- `OperationLockService` — mantém o nome; muda o miolo.

---

## 22. Watcher

Detecção fica na App (`ReadDirectoryChangesW`/USN via FFM é barata: um handle e um poll). O watcher
deixa de chamar `InventoryBatchLauncherService` e passa a **enfileirar** `INVENTORY`, com a
deduplicação da §13 substituindo o `inventoryPending` em memória — que hoje se perde no restart.
`ReconcileScheduler` idem.

---

## 23. Banco e migrations

Breaking, como autorizado:

**Em `execution`:** `PENDING`/`RUNNING` no lugar de `STARTED`/`SCANNING_FILES`/`PROCESSING_FILES`
(com transporte: em progresso → `INTERRUPTED`); `phase`, `claimed_by`, `claimed_at`, `lease_until`,
`priority`, `claim_count`, `cancel_requested`, `available_at`, `dedup_key`, `request_payload jsonb`.

**Schema conceitual final de `execution`** — as colunas do modelo de fila, além das que a entidade já
tem (tipo, gatilho, caminhos, contadores, `statusMessage`, `applicationVersion`, tempos):

| Coluna | Tipo | Papel |
| --- | --- | --- |
| `status` | enum | ciclo de vida da fila: `PENDING`, `RUNNING` e os terminais (§11) |
| `phase` | enum, nulo | fase funcional (`SCANNING`, `PROCESSING`, …), só para a tela de progresso |
| `claimed_by` | text, nulo | identidade do Worker que reservou |
| `claimed_at` | timestamp, nulo | quando reservou |
| `lease_until` | timestamp, nulo | **posse**, renovada a cada 30 s; nunca prazo de processamento |
| `priority` | int | base do aging (§12) |
| `claim_count` | int, default 0 | **monotônico**; incrementa só após os locks, antes de executar |
| `cancel_requested` | boolean | pedido do usuário, lido entre lotes |
| `available_at` | timestamp | antes disso não é elegível (backoff/jitter) |
| `dedup_key` | text, nulo | caminho canônico ou constante; **nulo desliga a dedup** (§13) |
| `request_payload` | jsonb, nulo | contrato versionado (§7); nulo nos tipos que não precisam |

Não há coluna `attempts`, e nenhuma coluna antiga é preservada por compatibilidade.

**Índices:** parcial de claim `(priority DESC, id) WHERE status = 'PENDING'`; `lease_until WHERE
status = 'RUNNING'`; único parcial de dedup.

**Tabela nova:** `self_written_path`.

**Removidas:** todas as `BATCH_*`.

O `AGENTS.md` exige que a migration transporte o dado: as execuções em progresso viram `INTERRUPTED`
na própria migration, e as terminais são remapeadas para o novo vocabulário de `status` com a fase
antiga preservada em `phase` quando fizer sentido.

---

## 24. Testes

| Camada | O quê | Como |
| --- | --- | --- |
| Unitário | elegibilidade, política de retry por causa, transições, dedup, envelhecimento | JUnit + relógio fixo |
| Integração (Testcontainers) | claim com `SKIP LOCKED`, expiração de lease, dedup por constraint, advisory lock entre duas conexões | PostgreSQL real |
| **Concorrência real** | **dois claimers disputando**, nenhum job pego duas vezes | duas threads/conexões contra o mesmo banco |
| Contexto | App sem beans de worker e worker sem beans de app | `@SpringBootTest` por profile |
| **Fora de processo** | **supervisão real**: subir o Worker de verdade, matá-lo, ver a App reiniciar; PID correto; `onExit()`; sem órfão; ffmpeg filho encerrado | poucos testes, `@EnabledOnOs(WINDOWS)`, auto-pulando no CI |
| Progresso/cancelamento | flag no banco observada pelo worker; UI vê o desfecho | integração |

O ponto crítico da arquitetura — **dois processos** — precisa de ao menos um teste que suba o segundo
processo de verdade. Simular tudo numa JVM testaria justamente o que a mudança não é.

---

## 25. Restore: o maintenance mode já existe, e passa a ser persistente

Investigação: **o produto já tem o conceito.** `CatalogBackupService.restore` chama
`backgroundWorkGate.restoreStarted()` *"before the first table is dropped and cleared only at the
end"*, e `RestoreInProgressInterceptor` (registrado no `WebMvcConfig`) segura as requisições enquanto
isso dura. O comentário no código explica por quê: *"Every periodic task in this process queries a
database that, for the next few minutes, is missing whatever pg_restore is recreating at that
instant"*.

O que muda: hoje o `BackgroundWorkGate` é **in-memory na App**, e o Worker não o enxerga. Ele passa a
ser respaldado por uma linha em `app_setting` (`maintenance.restore-in-progress`), que já é o lugar de
configuração global do produto — **sem tabela nova**.

**Protocolo do restore, na ordem:**

```
1. App: backgroundWorkGate.restoreStarted()  → grava o flag e bloqueia HTTP (interceptor de hoje)
2. App: watcher e timers param de enfileirar (leem o mesmo gate)
3. App: Explorer rename/delete recusam com mensagem localizada  ← modal, como manda o AGENTS.md
4. App: pede shutdown ordenado ao Worker (§18) e aguarda Process.waitFor
        — jobs ativos terminam ou viram INTERRUPTED; advisory locks caem com as conexões
5. App: fecha o pool do Hibernate (nenhuma conexão da aplicação sobra)
6. pg_restore
7. App: reconecta, Flyway migra o dump antigo para frente (comportamento já documentado)
8. App: valida a versão de schema (§17)
9. App: sobe o Worker de novo
10. App: backgroundWorkGate.restoreFinished() → limpa o flag, libera HTTP
```

O passo 4 é a adição real: hoje não há Worker para drenar. O passo 5 importa porque `pg_restore`
falha ou fica pela metade com conexões abertas sobre os objetos que ele recria.

**Backup (`pg_dump`) não precisa de nada disso** — ele lê num snapshot consistente e não derruba
ninguém. O `restoreStarted()` de hoje já é chamado só no restore, e essa assimetria está correta.

---

## 26. `-threads` do ffmpeg: onde aplica e onde é ilusão

A afirmação anterior ("a única forma correta de conter ffmpeg é `-threads N`") era genérica demais.
Mapeando os comandos reais do projeto:

| Uso | Comando (essência) | `-threads` ajuda? |
| --- | --- | --- |
| Extração de frame para pHash / thumbnail | `ffmpeg -v error -ss T -i in -frames:v 1 -q:v N out.jpg` | **quase nada** — decodifica um frame e sai; o custo é o *seek*, que é I/O. Aplicar `-threads` aqui é ruído |
| Fingerprint de vídeo (vários frames) | idem, repetido, com `-vf` | pouco — mesma natureza, custo em I/O |
| Sondagem (`ffprobe`) | `ffprobe -v ... -print_format ...` | **não** — `ffprobe` não codifica nada |
| **Conversão H.265** | `ffmpeg -i in -c:v libx265 ... out` | **sim, e é o único que importa** — libx265 respeita `-threads` (e tem `x265-params pools`), e é o que satura a máquina por horas |

**Conclusão: `-threads` entra apenas no comando de conversão.** Nos demais seria cerimônia sobre
processos de vida curta cujo gargalo não é CPU — e o `AGENTS.md` recusa configuração que não paga.

Para os comandos curtos, **`ExternalToolGate` continua sendo o único controle prático e é suficiente**:
limitar quantos coexistem já limita o consumo, porque cada um custa pouco e dura pouco.

O que nenhum dos dois faz, dito com precisão: `-threads` limita *threads de codificação*, não o
processo inteiro; e o `ExternalToolGate` limita *quantidade simultânea*, não consumo por processo. A
prioridade do processo Worker não se propaga aos filhos ffmpeg automaticamente — se for desejável, é
o mesmo ajuste por PID da §3, aplicado ao filho no momento em que ele é criado.

---

## 27. O que a App precisa carregar para *pedir* um job

Risco apontado, e legítimo: separar por profile não pode tornar o modelo impossível de compartilhar.
A App **cria** a `Execution` e o `request_payload`, então precisa do contrato — só não pode precisar
do executor.

Corte em três, e o profile atua **só na terceira coluna**:

| Camada | Exemplo | Onde vive | Profile |
| --- | --- | --- | --- |
| **Contrato do pedido** | records de payload em `<domínio>/application/dto`, `ExecutionType`, validação dos parâmetros, `ExecutionQueue` | ambos | **nenhum** — classes puras, sem `@Profile` |
| **Entidades e repositórios** | `Execution`, `Movement`, `CatalogFile`, ports | ambos | nenhum |
| **Execução física** | `OrganizationExecutor`, `VideoConversionService`, `ProcessingCoordinator`, `ExternalToolGate`, runners de ferramenta | só Worker | `@Profile("worker")` na `@Configuration` |
| **Entrega e controle** | controllers, Thymeleaf, bandeja, watcher, timers, `EmbeddedClusterService`, Flyway, update | só App | `@Profile("app")` |

Regra prática: **o profile marca configurações, não modelos.** Um record de payload, um enum ou uma
interface de port nunca leva `@Profile` — se levasse, a App não conseguiria sequer descrever o que
quer pedir. Validar o pedido é responsabilidade da App (é ela que fala com o usuário e devolve
mensagem localizada); executar é do Worker.

Isso também evita o inverso: a App não instancia `ProcessingCoordinator`, pools de processamento nem
gates de ferramenta externa, que são o peso que a separação existe para tirar dela.

---

## 19.1 O reconcile agendado: decisão por custo medido

O `ReconcileScheduler` era o candidato mais forte a ficar na App — parecia leve e, ao virar job,
poluiria a tela de Execuções com passagens que não fazem nada. **Foi medido antes de decidir.**

**O que uma passagem vazia faz**, do `ReconcileScheduler.runOnce` até o fim:

- **Filesystem:** `Files.walkFileTree` sobre a árvore inteira, com `isRegularFile`, `isHidden` e
  normalização por arquivo. **O(N) no número de arquivos em disco**, não no catálogo — e sem atalho,
  porque a comparação exige a lista completa.
- **Banco:** `ceil(M/1000) + 1` consultas, `M` = localizações catalogadas sob a pasta, em páginas de
  1.000 via `findForReconcile`. Para 150 mil arquivos, **151 consultas**.
- **Memória:** dois conjuntos de caminhos (disco e catálogo) vivos ao mesmo tempo.

**Medição:** árvore sintética de 20.000 arquivos, cache quente, SSD, três rodadas — 738/864/798 ms,
ou **~40 µs por arquivo**. Extrapolando para os 145 mil arquivos que o README usa como referência:
**~6 s por passagem**, e esse é o melhor caso (arquivos vazios, cache quente, disco rápido).

**Frequência real:** `reconciliation-interval-millis = 300000`, ou seja **5 minutos** — 288 passagens
por dia, ~29 minutos de varredura de disco diários.

**Decisão: o reconcile agendado vai para o Worker.** Não por pureza arquitetural — por custo medido.
O `ReconcileScheduler` passa a apenas enfileirar `RECONCILE` com `trigger = TIMER`, e a dedup
(1 PENDING + 1 RUNNING por caminho) impede acúmulo quando uma passagem dura mais que o intervalo.

### Contadores com significado, e o histórico funcional

O recorder antigo terminava um reconcile com **todos os contadores zerados**, mesmo tendo reparado —
o que foi reparado vivia só na mensagem, como código de i18n. Nada podia perguntar "quantos itens
esta execução mudou".

`repaired_items` (migration `V18`) responde. É coluna de **conteúdo**, não de visibilidade: diz o que
a execução fez, e continuaria significando o mesmo se a tela mudasse de ideia.

**Deliberadamente não é `filesMoved`.** Esse contador significa "a aplicação moveu estes arquivos no
disco" em organização, undo e dedup; um reconcile não move nada — registra movimentos feitos por
fora. Reusá-lo faria a tela dizer "Movidos: 3" para uma execução que não tocou em arquivo algum.

**Contagem de itens distintos, não soma.** `markedMissing` já exclui o que foi renomeado ou reparado,
mas seguir um rename e sincronizar um caminho obsoleto são decididos independentemente e podem cair
na mesma entrada — somar reportaria dois reparos onde um item mudou.

Mapeamento final: `filesFound` = arquivos encontrados no disco (o nome já dizia isso);
`repaired_items` = entradas de catálogo corrigidas, contadas uma vez; `renamed`/`repairedPaths`/
`markedMissing` seguem detalhados na mensagem.

### Fila técnica ≠ histórico funcional

**A linha nunca some do banco.** A fila e a auditoria técnica são completas — toda execução é uma
linha, incluindo as centenas de reconciles automáticos de uma biblioteca parada.

O **histórico funcional** é o que a tela mostra, e a única coisa que ele omite é
`RECONCILE` + `TIMER` + `FINISHED` + `repaired_items = 0`. Manual, de recuperação, com qualquer
reparo, `ERROR`, `INTERRUPTED` ou `CANCELLED` aparecem sempre — cada um é algo que alguém pode
precisar explicar. Sem coluna de visibilidade, sem poda, sem heurística, e sem filtrar por chave de
i18n.

---

## 20.1 Empacotamento: o que o Worker instalado usa

**Tudo vem da própria instalação.** O binário Java é `java.home` — que, numa cópia instalada, é o
runtime que o jpackage embarcou. O jar é o que carregou a própria classe, resolvido em caminho
**absoluto**. Nada é procurado no PATH: uma máquina com outro JDK à frente não decide em qual Java o
worker roda.

O caminho absoluto não é preciosismo. `java.class.path` é **relativo ao diretório de trabalho** quando
o processo foi iniciado com `-jar`, e o worker herda um diretório de trabalho que ninguém escolheu —
depois de instalar em `C:\Program Files\Nimbus File Manager\`, um classpath relativo apontaria para o
lugar errado. Fora de um jar (IDE, suíte), não há jar para apontar e o classpath é repassado como
está.

**Nenhum segundo launcher no MSI.** App -> `ProcessBuilder` -> runtime embarcado -> mesmo jar +
profile `worker` basta, e um executável a mais seria outra coisa para versionar, assinar e manter em
sincronia.

### Opções de JVM: o que se repete e o que não

| Opção | App | Worker | Por quê |
| --- | --- | --- | --- |
| `-Xms`/`-Xmx` | launcher (jpackage) | `ProcessBuilder` | orçamentos independentes; é o ganho da separação |
| `--enable-native-access=ALL-UNNAMED` | launcher | **não** | existe para as chamadas FFM do watcher a kernel32, e o watcher é papel `app` |

**Opções de JVM não são herdadas por processo filho** — cada uma que o worker precisa é passada
explicitamente. O acesso nativo restrito foi verificado: `java.lang.foreign` aparece apenas em
`inventory/infrastructure/watch/source/**`, que só existe no papel da aplicação. Conceder a flag a um
worker que não faz acesso nativo seria liberar o que nada pede.

### Estado do Worker na bandeja: avaliado e não implementado

O supervisor tem o estado (`isRunning`), mas expô-lo na bandeja exigiria um evento novo, um listener
em `infrastructure/desktop`, chaves de i18n por estado e uma segunda leitura de saúde — para
informação que a tela de Execuções já dá melhor:

- um worker que caiu é **reiniciado sozinho**, então "parado" seria um estado quase sempre invisível;
- quando o processamento realmente não anda, o sintoma que o usuário percebe é execução parada em
  PENDING — e isso está na tela de Execuções, que é onde se olha;
- a bandeja hoje abre e encerra o produto; virar painel de monitoramento é outra responsabilidade.

**Decisão: não implementar.** Se um dia o worker deixar de ser reiniciado automaticamente, a conta
muda e isto volta à mesa.

### O que só o MSI real valida

Automatizado até aqui: o handle é do processo iniciado, ele não sai sozinho, `onExit` dispara,
`stop()` encerra o processo real, nenhum órfão fica, e o comando leva runtime/jar/profile/heap certos.

**Aceitação manual do pacote** (fora da suíte, porque instalar um MSI dentro dela testaria o
instalador do Windows):

1. a aplicação abre;
2. nasce **exatamente um** worker externo;
3. o worker roda no runtime embarcado (o caminho do processo aponta para dentro da instalação);
4. aplicação e worker têm pids distintos;
5. encerrar pela bandeja encerra o worker;
6. matar o worker à mão faz a aplicação iniciar outro;
7. update e uninstall não deixam worker nem ffmpeg órfãos;
8. o combined no Eclipse não cria uma segunda JVM.

---

## Invariantes do A8

Lista curta e estável. Cada uma deve ser afirmada por teste; se alguma cair durante a implementação, é
sinal de que uma decisão precisa voltar à mesa, não de que o teste está chato.

1. **Uma `Execution` nunca é executada por dois dispatchers ao mesmo tempo** — garantido pelo claim
   com `FOR UPDATE SKIP LOCKED` e pela transição `PENDING → RUNNING` na mesma transação.
2. **Operações conflitantes por caminho nunca coexistem**, entre App e Worker ou entre dois jobs —
   garantido pelo advisory lock sobre a cadeia de prefixos canônicos.
2b. **Uma operação exclusiva sobre um escopo impede operações no próprio escopo e em seus
   descendentes, mas operações sobre árvores irmãs independentes não se bloqueiam apenas por
   compartilharem ancestrais** — ancestrais são tomados em modo compartilhado (§9.3.1).
3. **Perder o PostgreSQL invalida a posse antes de qualquer mutação nova** — nenhum `SecureFileMove`
   começa com a posse sabidamente perdida (§9.6).
4. **O row lock da fila nunca fica aberto durante a execução** — a transação de claim termina antes de
   o job começar.
5. **`lease_until` significa posse, nunca prazo de processamento** — um job renovado corretamente pode
   durar horas.
6. **`claim_count` é monotônico.** Ele só incrementa quando a Execution adquiriu seus locks e está
   prestes a iniciar processamento efetivo. **Nunca decrementa**, por shutdown, cancelamento, disputa
   de lock ou qualquer outra razão. É o único freio de poison job.
7. **`PENDING` sobrevive a restart de App, de Worker e de banco** — nada de fila em memória.
8. **Cancelamento do usuário (`CANCELLED`) é distinto de shutdown administrativo (`INTERRUPTED`)**, no
   estado persistido e no que a tela mostra.
9. **A App nunca executa processamento pesado** que pertence ao Worker.
10. **O Worker nunca supervisiona PostgreSQL, nunca roda Flyway, nunca inicializa bandeja e nunca
    instala atualização da App.**
11. **Rename e delete do Explorer continuam síncronos**, na thread HTTP, com exclusão cross-process.
12. **Escrita própria nunca causa rajada de inventário, e mudança externa nunca é descartada em
    silêncio** — a supressão é por caminho anunciado e expira sempre.
13. **Não existe Spring Batch nem qualquer segundo modelo persistente de execução.**
14. **Nenhum ffmpeg fica órfão após shutdown normal do Worker.**
15. **Nenhuma falha de Worker produz laço infinito** — o corte está no `UPDATE` condicional do
    incremento, não numa checagem em memória.
16. **Nenhum código de domínio, filesystem, ffmpeg ou processamento é chamado entre a aquisição do
    `OperationLock` e a persistência bem-sucedida de `claim_count++`** — se o incremento não afetar
    exatamente uma linha, os locks são liberados e nada executa (§9.4.1).

---

## Decisões finais antes da implementação

| Decisão | Escolha | Evidência no código | Alternativa rejeitada | Motivo |
| --- | --- | --- | --- | --- |
| Supervisor real | Contexto Spring da App | `TrayLifecycle` roteia "Exit" por `SpringApplication.exit`; `EmbeddedClusterService` já é filho supervisionado | Windows Service; launcher nativo | O padrão já existe e está endurecido para o PostgreSQL |
| App crasha | Worker termina o job e encerra | — | Worker sobreviver | Sem App não há UI, cancelamento nem supervisão; e a próxima abertura criaria um segundo Worker |
| Início do Worker | `ProcessBuilder` direto, retendo `Process` | o `cmd /c start` do `nimbus-update.cmd` esperou o processo errado e travou a atualização | `cmd /c start /belownormal` | Perde PID, `onExit()` e `destroy()` |
| Prioridade | ajuste pós-start por PID | — | prioridade via `cmd start` | Supervisão tem precedência |
| Spring Batch | **REMOVER** | `ExecutionContext` aberto sem cursor; `StartupExecutionRecoveryListener` diz "no cursor is persisted"; restart nunca usado | Manter | Paga-se tabelas e uma segunda noção de execução sem receber restart |
| Exclusão de caminho | Advisory lock de sessão: escopo **exclusivo**, ancestrais **compartilhados** | `OperationLockService` já é por caminho e tem `acquire`/`acquireWithin`; o teste de irmãs provou que ancestral exclusivo serializa o volume | Tabela de locks; `FOR UPDATE` longo; **toda a cadeia exclusiva** | Zero escrita, liberação automática na queda da conexão; e cadeia exclusiva transformaria `D:\` num mutex global |
| Explorer rename/delete | **Continuam síncronos na App** | são milissegundos numa thread HTTP | Virar job | Degradaria UX por conveniência arquitetural |
| `SelfWrittenPathRegistry` | Tabela consultada (não consumida), expirando com a Execution + TTL | `FileNotifyInformationParser` ignora `FILE_ACTION_*`; `RdcwChangeInterpreter` só deduplica **dentro de um poll** | Consumo único via `DELETE ... RETURNING` | Uma escrita longa gera eventos em polls sucessivos; o primeiro consumiria o anúncio e os demais disparariam inventário |
| Chave de lock | 64 bits de SHA-256 do caminho canônico | SHA-256 já usado no `SecureFileMove` | `String.hashCode()` | Não é estável entre versões de JVM |
| Ordem de aquisição | `TreeSet<Long>`, crescente, sempre | — | Ordem de uso / timeout como rede | Ordem total elimina deadlock por construção; timeout seria remendo |
| Conexão de lock | Uma por Execution ativa, fora do Hikari | advisory lock é propriedade da sessão | Conexão de lock única do Worker | Um job liberaria o lock de outro |
| Detecção de perda de posse | Renovador consulta `pg_locks` por `pg_backend_pid()` a cada 30 s | — | `SELECT 1` | Conexão reconectada responderia `1` sem lock nenhum |
| Dedup DDL | **Dois** índices parciais (PENDING e RUNNING separados) | `inventoryPending` exige 1 rodando + 1 esperando | Índice único sobre `status IN (...)` | Proibiria justamente PENDING+RUNNING |
| Aging vs índice | Scan dos PENDING, assumido | fila de dezenas de linhas | Prioridade materializada | Complexidade sem problema que a justifique |
| Worker sem banco | Encerra sem persistir; lease resolve | o PostgreSQL pode morrer junto com a App | Bloquear a saída até persistir | Órfão mexendo em arquivos é o pior resultado |
| Restore | `BackgroundWorkGate` promovido a `app_setting` + drenagem do Worker | `restoreStarted()` e `RestoreInProgressInterceptor` **já existem** | Tabela nova de maintenance | O conceito já está no produto |
| `-threads` do ffmpeg | **Só** na conversão H.265 | extração de frame é `-frames:v 1`, custo em seek | `-threads` em todos os comandos | Ruído em processos de vida curta |
| Estados | `status` = fila; `phase` = fase funcional | `IN_PROGRESS_NAMES` existe para agrupar as três fases | Só adicionar `PENDING` | `status` faz duas funções hoje |
| `request_payload` | Record por tipo, `schemaVersion`, campo novo opcional | 5 de 13 tipos não precisam de payload | `Map<String,Object>` | PENDING criado na versão X é consumido na X+1 |
| Lease | Posse, renovado por thread dedicada a cada 30 s | thread de trabalho pode bloquear em `waitFor()` do ffmpeg | Heartbeat pela thread de trabalho; lease como duração máxima | Job legítimo perderia posse |
| Concorrência entre Executions | `ExecutionDispatcher` no Worker | — | Um job por vez | Há operações que coexistem hoje |
| Paralelismo interno | `ProcessingCoordinator`, como hoje | backpressure já implementado | Reimplementar | Já resolve |
| ffmpeg simultâneo | `ExternalToolGate`, como hoje | semáforo por categoria | — | Ortogonal aos outros dois |
| Dedup | Índice único parcial | `inventoryPending` em memória se perde no restart | SELECT-então-INSERT | Corrida |
| `claim_count` | Incrementa **somente após os advisory locks**, imediatamente antes do início efetivo; monotônico; limita poison job | a tentativa só é real quando os recursos são obtidos | Incrementar na reserva e reverter na disputa de lock | Contador que anda para trás deixa de significar o que o nome diz, e a reversão teria janela de crash |
| Retry | Por categoria de causa | `MoveIntegrityException` nunca deve repetir | `catch(Exception) → PENDING` | Repetir entrada ruim não a conserta |
| Shutdown | Graça de 30 s → `INTERRUPTED`, **sem alterar `claim_count`** | runners já fazem `destroyForcibly` no teto | Marcar tudo `INTERRUPTED` na hora; devolver crédito | Perderia lote quase pronto; e compensação quebraria a monotonicidade |
| Update/tools/backup | **Ficam na App** | update encerra a própria aplicação; restore derruba conexões | Mandar tudo ao Worker | Worker não pode substituir seu supervisor |
| Flyway | Só na App; Worker valida versão e recusa | — | Worker migrar | Corrida de migration entre processos |
| `-XX:ActiveProcessorCount` | Usado para dimensionar pools, **descrito como tal** | — | Descrever como cota de CPU | Não é cota, e não alcança o ffmpeg |
| ffmpeg CPU | `-threads N` na linha de comando | não existe hoje | Confiar no gate | Gate limita quantidade, não consumo |
| Nome do port | `ExecutionQueue` | decisão de não ter `Job` | `JobQueue` | Reintroduziria o conceito descartado |

---

## Sequências

**Enfileirar → claim → executar → concluir**

```
UI/timer/watcher → ExecutionQueue.enqueue(tipo, payload, dedupKey, priority)
                   INSERT ... status=PENDING            [viola índice único → já existe, fim]
Worker dispatcher → tem vaga? (global + categoria)
                   RESERVA: BEGIN; SELECT ... FOR UPDATE SKIP LOCKED;
                            UPDATE→RUNNING, claimed_by, lease; COMMIT   (claim_count intocado)
                   registra id no renovador
                   OperationLockService.acquire(paths)  ← advisory lock, conexão dedicada
                   locks obtidos → UPDATE claim_count+1 com guarda atômica (§9.4)
                                   afetou 1 linha? não → libera locks, não executa
                   executa (ProcessingCoordinator por dentro; ExternalToolGate no ffmpeg)
                   escreve progresso/contadores (a UI já lê isso)
                   COMMIT final: status terminal, phase=null, libera advisory lock
                   remove id do renovador
```

**Cancelamento**

```
UI → POST cancel → UPDATE execution SET cancel_requested = true
Worker (no ponto de checagem entre lotes, no mesmo SELECT do próximo lote)
     → vê a flag → aborta com segurança → limpa temporário → status=CANCELLED
UI (polling que já existe) → mostra "Cancelado"
```

**Crash do Worker**

```
Worker morre → renovador para → lease_until vence
App detecta via Process.onExit() → reinicia (backoff)
Worker novo, no start: reclaim de execuções cujo lease venceu
   tipo retomável  → PENDING (claim_count preservado, já contado se chegou a iniciar)
   tipo mutável    → INTERRUPTED + enfileira RECONCILE da pasta
   CONVERSION      → INTERRUPTED + apaga .part daquele publicId
```

**Crash da App** — e o PostgreSQL pode ter ido junto

O PostgreSQL é filho supervisionado pela App, e o próprio código registra que uma saída abrupta pode
deixá-lo vivo (é a razão de `ApplicationShutdown` e `TrayLifecycle` existirem). Logo, os dois casos
são reais e o Worker **não pode depender do banco para conseguir encerrar**:

```
App morre → Worker vê ProcessHandle(appPid).onExit()
  (A) banco ainda vivo   → para claims → encerra lote → INTERRUPTED persistido → sai
  (B) banco indisponível → para claims → encerra lote → NÃO tenta persistir → sai
                           (o lease vence sozinho; a recuperação do próximo start resolve)
```

**Ordem de prioridade após a morte da App, nesta sequência e sem inversão:**

1. não iniciar trabalho novo;
2. deixar o filesystem consistente no melhor esforço (terminar o move atômico em curso);
3. matar os ffmpeg filhos;
4. sair.

Persistir estado é **desejável, nunca bloqueante**: a tentativa de escrever `INTERRUPTED` tem timeout
curto (5 s) e a falha é logada em DEBUG, não em ERROR — é situação esperada, como manda o
`AGENTS.md`. Um Worker que não consegue morrer porque o banco sumiu seria o pior resultado possível:
processo órfão, mexendo em arquivos, sem supervisor.

**Shutdown normal**

```
Bandeja "Sair" → SpringApplication.exit
  → App: pede shutdown ao Worker
  → Worker: para claims, sinaliza jobs, graça de 30s, INTERRUPTED no que sobrar,
            destroy nos ffmpeg, fecha DataSource, sai
  → App: aguarda Process.waitFor(timeout) → EmbeddedClusterService.stop() → exit
```

---

## O que será removido

> **Executado.** Cada item saiu no mesmo passo em que seu substituto entrou, como o plano previa. O
> que a Fase 4 acrescentou a esta lista: `OrganizationPlanStore` e `OrganizationAsyncRunner` (4.2.7),
> `SimilarityGroupCache` e os dois runners de similaridade (4.2.6), e a própria janela de manutenção
> global — `MaintenanceWindow`, `backgroundWorkPaused()` e `ExecutionLockKeys` — que existia apenas
> para a troca de biblioteca e saiu com ela (4.2.8).
>
> A Fase 5 continuou a lista: os dois runners de fingerprint, seus dois beans de startup, o
> `FingerprintBacklogResumer` e a tabela `fingerprint_job_run` (5.1) — e, com eles, o pool de análise
> visual do `AsyncConfig`, que existia só para esses dois e ficou sem ninguém para submeter.
>
> A 5.2 levou o `MetadataRebuildAsyncRunner` e o `if (dryRun)` que fazia um método significar duas
> coisas; o `POST /api/metadata/rebuild` deixou de rodar dentro da requisição.
>
> A 5.3 levou o `LocationRebuildAsyncRunner`, o `GeoDatasetAsyncRunner`, o `lastResult`/`lastError`
> que duplicavam o `metadata.json`, e o `InventoryScanAsyncRunner` que já era código morto. O
> `GeoDatasetProgress` ficou, mas deixou de ser a resposta: ele escreve na linha e ninguém o lê.
>
> Nenhum `AtomicBoolean` de lifecycle sobrou. Os três runners que permanecem têm os seus, e é por
> decisão — §19 diz de cada um por quê.


- `AsyncConfig` e os pools; `@Async` dos onze runners de processamento.
- Os `AtomicBoolean running` de backup, conversão, dedup, fingerprint, similaridade.
- `ExecutionCancellationService` in-memory.
- `InventoryWatchService.inventoryPending`.
- **Spring Batch inteiro**: dependência, `InventoryJobConfig`, `InventoryJobExecutionListener`,
  `InventoryBatchLauncherService`, `InventoryBatchAsyncRunner`, as interfaces do Batch em reader/
  processor/writer, `spring.batch.*`, tabelas `BATCH_*` e seus testes.
- `ExecutionStatus.STARTED`, `SCANNING_FILES`, `PROCESSING_FILES` e `ExecutionStatusNames.IN_PROGRESS*`.
- O `synchronized`/`HashMap` interno do `OperationLockService` (a classe fica).
- `SelfWrittenPathRegistry` em memória (o conceito fica, a tabela substitui).
- Testes que afirmam unicidade via `AtomicBoolean` e os que exercitam job/step do Batch.

---

## Plano de implementação

> **Executado, nos nove passos previstos.** O passo 9 - a limpeza - foi feito por fatia em vez de ao
> final, o que a Fase 4.2 chamou de "o antigo sai no mesmo passo que o novo entra". O plano
> subestimou uma coisa e acertou o resto: eliminar o último alcance da App a um port de mutação exigiu
> cinco fatias (4.2.4 a 4.2.8), não um passo.


Todos os passos constroem o estado final; nenhum cria modo que depois sai.

1. **Modelo e migration** — novos estados, `phase`, colunas de fila, índices, `self_written_path`,
   remoção das `BATCH_*`, transporte dos dados.
2. **`ExecutionQueue` + adapter JDBC** — claim com `SKIP LOCKED`, lease, dedup por constraint,
   envelhecimento. Testes de concorrência com dois claimers.
3. **`OperationLockService` sobre advisory lock** — mesma API, exclusão cross-process. É o passo que
   destrava tudo, e o de maior risco.
4. **Cancelamento e recuperação persistentes** — flag, política por tipo, reclaim por lease.
5. **Remoção do Spring Batch** — inventário passa a `ProcessingCoordinator` + serviços.
6. **Worker**: profile, dispatcher, renovador, `ProcessingCoordinator` e `ExternalToolGate` movidos.
7. **App vira produtora e supervisora** — runners viram enfileiramento; watcher e timers enfileiram;
   `ProcessBuilder` + `onExit` + shutdown ordenado; update/tools/backup permanecem.
8. **Empacotamento e lifecycle** — jpackage/MSI, bandeja, update e uninstall cientes do Worker;
   testes fora de processo.
9. **Limpeza** — remoção do que a lista acima prevê, no mesmo passo em que cada substituto entra.

Até o passo 5 nada consome a fila; no 6–7 a execução migra inteira. Em nenhum momento existem dois
motores de execução em produção.

---

## Riscos que permanecem

1. **Advisory lock por prefixo** é a peça nova mais delicada: a profundidade de prefixos e o
   mapeamento caminho→chave precisam ser exatos, ou a exclusão falha em silêncio. Mitigação: teste de
   integração com duas conexões e caminhos aninhados, incluindo acentuação e caminho longo.
2. **A janela entre mover arquivo e escrever no banco** não desaparece — só é detectável e
   reconciliável. Aceita conscientemente.
3. **Dois processos disputando disco** continua real; os controles da §20 atenuam, não eliminam.
4. **Remoção do Spring Batch** toca o caminho mais exercitado do produto (inventário). É a maior
   massa de código do plano, e o passo que mais precisa de cobertura antes de mexer.
---

## Estado vigente — A8 CONCLUÍDO

Encerrado na versão 8.1.1.197.

Esta seção deixou de ser a referência do que existe: ela foi substituída por
[`docs/architecture/worker-architecture.md`](../architecture/worker-architecture.md), que descreve a
arquitetura vigente sem exigir a leitura deste plano. O que fica registrado aqui é **o desfecho do
A8**, para quem estiver lendo o documento histórico.

### As decisões permanentes viraram ADR

| ADR | Assunto |
| --- | --- |
| [0003](../adr/0003-app-e-worker-como-processos-separados.md) | App e Worker como processos separados |
| [0004](../adr/0004-execution-como-protocolo-duravel.md) | `Execution` como protocolo durável de comando e execução |
| [0005](../adr/0005-claim-lease-e-recuperacao.md) | Claim, lease, vivacidade e recuperação |
| [0006](../adr/0006-concorrencia-de-mutacao-do-filesystem.md) | Concorrência de mutação: exclusão por caminho |
| [0007](../adr/0007-resultados-derivados-duraveis.md) | Resultados derivados duráveis e sua publicação |
| [0008](../adr/0008-operacoes-assincronas-da-app.md) | As três operações assíncronas que permanecem na App |

### As fases do plano, no fim

| Fase | Estado |
| --- | --- |
| 0 a 5 | **concluídas** — a auditoria registra cada fatia |
| 6 (workloads pesados que não escrevem) | **concluída** — executada como Fase 5, fatias 5.1 a 5.3 |
| 7 (provar o invariante por teste) | **concluída** — `HeavyWorkloadArchitectureTest`, na 5.3 |
| 8 (limpeza: pools, `@Async`, runners, `AtomicBoolean`, `isLive`) | **concluída** — o `isLive` foi o último item. O `inventoryPending` permanece e não é dívida: é debounce do watcher, nunca foi ciclo de vida de `Execution` |
| 9 (reconciliar o documento com o código) | **concluída** — formalizada por este fechamento |

Nenhuma seção acima deve ser lida como trabalho futuro.

### O que ficou fora do A8

Empacotamento e ciclo de vida de processo não fazem parte do núcleo arquitetural e seguem como
backlog próprio: a aceitação manual do MSI (§20.1 deste documento), o comportamento de update e
uninstall com Worker vivo, e a supervisão de processos no pacote instalado. **Estado do Worker na
bandeja é decisão tomada — não implementar**, registrada no ADR 0008; não é pendência.