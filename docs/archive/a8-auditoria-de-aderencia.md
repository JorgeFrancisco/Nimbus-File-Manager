# A8 — Auditoria de aderência entre documento e código

> **ARQUIVADO — o A8 está concluído.** Este é o **diário da execução**: cada reconciliação feita
> antes de tocar em código, cada achado, cada decisão de fatia e cada gate. Duzentas seções de
> evidência, preservadas porque é aqui que se descobre *por que* uma coisa é do jeito que é quando o
> ADR não basta.
>
> **Não leia isto para saber como o sistema funciona hoje.** Para isso:
>
> | Pergunta | Onde |
> | --- | --- |
> | Como a arquitetura funciona hoje | [`docs/architecture/worker-architecture.md`](../architecture/worker-architecture.md) |
> | Por que as decisões permanentes foram tomadas | [`docs/adr/`](../adr/) — ADRs 0003 a 0008 |
> | O plano original e sua evolução | [`a8-processamento-em-worker-separado.md`](a8-processamento-em-worker-separado.md) |
>
> O fechamento está na última seção, VIII.200.

Levantada sobre a árvore da versão 6.8.3.179, **sem alterar código**. A fonte de verdade da
implementação é o código; o relatório dos passos anteriores não é evidência.

Motivo original: o A8 afirma que ao fim dos passos 6–7 a execução migraria por inteiro e que não
existiriam dois motores em produção. A varredura do passo 9 mostrou que apenas `INVENTORY` e
`RECONCILE` migraram.

> **Este documento tem duas partes.** A **Parte I** é a auditoria própria, em segunda passada. A
> **Parte II** é a Fase 0 do plano de execução: reconciliação ponto a ponto com a auditoria
> independente do Codex, verificada no código. **Onde as duas partes divergirem, prevalece a Parte
> II** — ela corrige, entre outras coisas, a afirmação de que `RECONCILE` estaria migrado.

**Esta é a segunda passada.** A primeira partiu de `ExecutionType` e chegou a números inconsistentes.
Esta parte de todos os `@Async`, runners, schedulers, controllers, do `ProcessingCoordinator`, do
`ExternalToolGate` e de todos os pontos de escrita no filesystem — e corrige a própria auditoria antes
de corrigir qualquer coisa no produto. Os erros da primeira estão listados na §3, não apagados.

---

## 1. Contagens corrigidas

A primeira auditoria dizia "15 no motor antigo, 8 sem `ExecutionType`". Ambos estavam errados.

| Grandeza | Primeira auditoria | Correto | Como se chega |
| --- | --- | --- | --- |
| Workloads reais na matriz | 20 | **23** | a primeira omitiu rename do Explorer, troca de biblioteca e geração de thumbnail, e não separava o que era decisão de escopo |
| No motor novo | 2 | **2** | `INVENTORY`, `RECONCILE` |
| Permanecem na App por decisão do A8 | 4 | **5** | update, ferramentas, backup/restore, exclusão pelo Explorer e **rename pelo Explorer** (§9 do A8 decide os dois juntos) |
| Fora do escopo do A8 | — | **1** | geração de thumbnail é sob demanda, por requisição |
| Sem classificação no A8 | — | **1** | troca de biblioteca |
| **Pendentes de migração** | 15 | **14** | 23 − 2 − 5 − 1 − 1 |
| Pendentes **com** `ExecutionType` | 7 | **7** | organização, undo, conversão, dedup delete, purge, cleanup, restore |
| Pendentes **sem** `ExecutionType` | 8 | **7** | os dois backlogs, as duas similaridades, os dois rebuilds e o dataset geográfico |

`ExecutionType` tem **9 valores** — `INVENTORY`, `ORGANIZATION`, `UNDO`, `QUARANTINE_RESTORE`,
`QUARANTINE_PURGE`, `QUARANTINE_CLEANUP`, `DEDUP_DELETE`, `CONVERSION`, `RECONCILE`. Dois passam pela
fila, sete não. Fecha com a linha acima.

### De onde veio o número 8

**Não existe workload de thumbnail rebuild no código.** O domínio `thumbnail/application` tem
`PhotoThumbnailService`, `VideoThumbnailService` e uma exceção; `PhotoThumbnailService.get(publicId,
requestedWidth)` gera **uma** miniatura sob demanda, na requisição que a pede, e grava no workspace.
Não há `@Async`, nem runner, nem varredura de biblioteca, nem `ExecutionType`.

`THUMBNAIL_REBUILD` aparece **duas vezes** no A8 (§7, tabela de `request_payload`; §10, tabela de
`dedup_key`) sempre ao lado de tipos que também não existem. É modelo conceitual — uma lista de tipos
que a fila **poderia** carregar —, não inventário do código. O 8 da primeira auditoria era 7 reais + 1
conceitual, misturados sem perceber.

Consequência: `THUMBNAIL_REBUILD` deve **sair** do A8, e não virar tarefa. Nunca houve o que migrar.

---

## 2. Matriz final de workloads

Levantada dos `@Async` (15 arquivos, 17 métodos), dos `*Runner`, dos `@EventListener`/schedulers, dos
controllers que disparam processamento e de todos os `Files.*` de escrita.

| # | Workload | Existe hoje? | `ExecutionType` | Produtor / disparador | Executor atual | Escreve no filesystem? | Pesado? | Destino no A8 | Situação |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Inventário | sim | `INVENTORY` | `InventoryLauncherService`, `InventoryWatchService` | **Worker** — `InventoryJobHandler` | não (só lê) | sim | Worker | **migrado** |
| 2 | Reconcile | sim | `RECONCILE` | `ReconcileScheduler` (TIMER), `ReconcileExecutionRecorder` | **Worker** — `ReconcileJobHandler` | **não** (`ReconcileApplier` só toca repositórios) | médio | Worker | **migrado** |
| 3a | Organização — execução | sim | `ORGANIZATION` | `OrganizationLauncherService` (tela) | **Worker** — `OrganizationJobHandler` | **sim** — `SecureFileMove` + `EmptyDirectoryCleaner` | sim (horas) | Worker | **migrado** (4A) |
| 3b | Organização — preview | sim | `ORGANIZATION` | `OrganizationService` (tela) | App — `OrganizationAsyncRunner` (`dryRun`) | **não** (zero mutação) | sim | Worker | **reclassificado — VII.2** |
| 4 | Undo de organização | sim | `UNDO` | `OrganizationUndoLauncherService` | **Worker** — `OrganizationUndoJobHandler` | **sim** — `SecureFileMove` | sim | Worker | **migrado** (4B) |
| 5 | Conversão de vídeo | sim | `CONVERSION` | `ConversionLauncherService` | **Worker** — `ConversionJobHandler` | **sim** — temp no workspace + `ConversionFilePlacement`/`SecureFileMove` | sim (horas, ffmpeg) | Worker | **migrado** (4C) |
| 6 | Exclusão de duplicados | sim | `DEDUP_DELETE` | `DuplicateDeletionLauncherService` | **Worker** — `DuplicateDeletionJobHandler` | **sim** — move p/ quarentena | médio | Worker | **migrado** (4D) |
| 7 | Purga de quarentena | sim | `QUARANTINE_PURGE` | `QuarantineLauncherService` (tela e passagem diária) | **Worker** — `QuarantinePurgeJobHandler` | **sim** — `Files.delete` do arquivo e da pasta `exec-<id>` | leve | Worker (§9, "×2") | **migrado** (4E) |
| 8 | Limpeza de ausentes | sim | `QUARANTINE_CLEANUP` | `QuarantineWebController` (clique) | App — **thread HTTP, síncrono** | não (só catálogo) | leve, com teto (`MAX_PER_RUN`) | Worker (§9, "×2") | **decisão reaberta — §7** |
| 9a | Restauração de quarentena — lote | sim | `QUARANTINE_RESTORE` | `QuarantineLauncherService` (tela) | **Worker** — `QuarantineRestoreJobHandler` | **sim** — `SecureFileMove` | leve–médio | Worker | **migrado** (4E) |
| 9b | Restauração de quarentena — item único | sim | `QUARANTINE_RESTORE` | `QuarantineWebController` (diálogo) | App — **síncrono** | **sim** — `SecureFileMove` | ms | Worker | **reclassificado — VII.2** |
| 10 | Exclusão pelo Explorer | sim | `DEDUP_DELETE` | `ExplorerDeletionService` | App — **síncrono** | **sim** — `DefaultExplorerFileSystem` (anuncia) | ms | **App** | por decisão |
| 11 | Rename pelo Explorer | sim | — | `ExplorerRenameService` | App — **síncrono** | **sim** — `SecureFileMove` p/ arquivo; **`Files.move` cru p/ pasta** | ms | **App** | por decisão, com lacuna (§6) |
| 12 | Backlog de pHash | sim | **nenhum** | `PhashBacklogStartup`, retomada pós-inventário | App — `PhashBacklogAsyncRunner` | não | sim (ffmpeg em massa) | **Worker** (§13) | pendente |
| 13 | Backlog de fingerprint de vídeo | sim | **nenhum** | `VideoFingerprintBacklogStartup` | App — `VideoFingerprintBacklogAsyncRunner` | não | sim | **Worker** (§13) | pendente |
| 14 | Similaridade de fotos | sim | **nenhum** | `DuplicatesWebController` | App — `PhotoSimilarityAsyncRunner` → `SimilarityGroupingRunner` | não | sim | **Worker** (§13) | pendente |
| 15 | Similaridade de vídeos | sim | **nenhum** | `DuplicatesWebController` | App — `VideoSimilarityAsyncRunner` → `SimilarityGroupingRunner` | não | sim | **Worker** (§13) | pendente |
| 16 | Rebuild de metadata | sim | **nenhum** | tela de settings | App — `MetadataRebuildAsyncRunner` | não | sim (exiftool/mediainfo) | **Worker** (§13) | pendente |
| 17 | Rebuild de localização | sim | **nenhum** | tela de settings | App — `LocationRebuildAsyncRunner` | não | sim | **Worker** (§13) | pendente |
| 18 | Dataset geográfico | sim | **nenhum** | scheduler de auto-update e settings | App — `GeoDatasetAsyncRunner` | sim, **fora da biblioteca** (workspace) | sim (download + import) | **Worker** (§13) | pendente |
| 19 | Troca de biblioteca | sim | **nenhum** | `LibrarySwitchService` (settings) | App — `@Async` | **sim** — `LibraryCatalogCleanupService.deleteIfExists` | médio | **não classificado** | **decisão aberta — §5** |
| 20 | Instalação de update | sim | — | tela de update | App — `UpdateInstallAsyncRunner` | sim (workspace/instalação) | médio | **App** (§13) | por decisão |
| 21 | Instalação de ferramentas | sim | — | settings e bootstrap | App — `ExternalToolInstallAsyncRunner` | sim (`tools/`) | médio | **App** (§13) | por decisão |
| 22 | Backup / restore do catálogo | sim | — | settings | App — `CatalogBackupAsyncRunner` (2 métodos) | sim (workspace) | sim | **App** (§13) | por decisão |
| 23 | Geração de thumbnail | sim | — | requisição HTTP que exibe a imagem | App — **síncrono, uma imagem** | sim (workspace) | não | não previsto | **fora do escopo** |
| — | *Thumbnail rebuild em lote* | **não existe** | — | — | — | — | — | `THUMBNAIL_REBUILD` (§7, §10) | **remover do A8** |

**Mecanismos que atravessam a matriz** — `ProcessingCoordinator` é usado por 4 workloads (inventário,
os dois backlogs e o motor de fingerprint); `ExternalToolGate` por 6 pontos (transcodificação,
mediainfo, os dois hashes perceptuais e os dois backlogs). Nenhum dos dois é workload: são limites
**dentro** de uma execução, e o §9 desta auditoria mostra por que não substituem o limite por
categoria.

**Fato que muda a leitura de risco:** os dois workloads já migrados são os únicos **que não escrevem
na biblioteca**. Inventário só lê; o reconcile repara catálogo (`ReconcileApplier` só toca
repositórios). É por isso que o `SelfWrittenPathRegistry` em memória ainda não causou incidente — e é
exatamente por isso que ele bloqueia a próxima migração.

---

## 3. Erros da primeira auditoria

| # | Afirmação da primeira passada | Realidade | Como o erro aconteceu |
| --- | --- | --- | --- |
| 1 | "15 seguem no motor antigo" | **14** | subtração errada sobre a própria tabela |
| 2 | "8 workloads sem `ExecutionType`" | **7** | contou `THUMBNAIL_REBUILD`, que só existe no A8 |
| 3 | "`QUARANTINE_CLEANUP` … nenhum runner … é registro de auditoria e não workload" | **é operação real**, disparada de `QuarantineWebController`, executada em `QuarantinePurgeService.cleanupAbsent()` sob `OperationLockService` | leu-se só `QuarantineOperationLog`, que abre a linha, e não quem a invoca |
| 4 | "`expiredLeases()` é código morto" | é a consulta do **reclaim por lease do Worker**, que o A8 especifica e o código não implementou | confundiu-se "sem chamador" com "sem propósito" |
| 5 | "`concurrencyLimit()`: implementar ou remover?" | é o **nível 1** de três que o A8 define; não há alternativa de remoção sem desfazer a decisão | tratou-se intenção arquitetural como escolha sobre um método |
| 6 | "os workloads sem `ExecutionType` entram na fila ou permanecem?" | **o A8 §13 já decidiu: Worker**, um a um, nominalmente | reabriu-se decisão fechada |
| 7 | Matriz sem rename do Explorer, troca de biblioteca e thumbnail | três workloads reais fora da conta | partiu-se de `ExecutionType` em vez dos `@Async` e dos pontos de escrita |
| 8 | Ordem propôs migrar writers **antes** do registry persistente | contradiz a própria conclusão da auditoria | a ordem foi escrita por domínio, não por dependência |
| 9 | "Bug de implementação — nenhum" | verdadeiro para regressão funcional, **mas escondia** invariantes não garantidas e código órfão | classificação grosseira demais (§11 corrige) |

**Divergência nova, encontrada só nesta passada:** `InventoryScanAsyncRunner` continua em
`src/main/java`, com `@Async(TASK_EXECUTOR)` e teste próprio, e **nenhum chamador de produção** — o
único consumidor de `InventoryScanRunner` hoje é o `InventoryJobHandler`. É resíduo da migração do
`INVENTORY` que a varredura do passo 9 não pegou. Código morto de verdade, e o único da lista.

---

## 4. Decisões que o A8 já tomou — não reabrir

O A8 nomeia cada runner e diz para onde vai (§13, tabela de migração) e nomeia cada uso de lock e diz
onde executa (§9, matriz dos onze usos). Onde ele decidiu, a ausência no motor novo é **trabalho
pendente**, não pergunta.

| Decisão do A8 | Onde | Estado |
| --- | --- | --- |
| Inventário, organização, preview, undo, reconcile → Worker | §9, §13 | 4 de 5 feitos — o preview foi reclassificado (VII.2) |
| Conversão, exclusão de duplicados, purga de quarentena → Worker | §9, §13 | **feitos** (VII.1) |
| Backlogs de pHash e de fingerprint de vídeo → Worker | §13 | pendentes, **e sem `ExecutionType`** |
| Similaridade de fotos e de vídeos → Worker | §13 | pendentes, **e sem `ExecutionType`** |
| Rebuild de metadata → Worker | §13 | pendente, **e sem `ExecutionType`** |
| Rebuild de localização e dataset geográfico → Worker | §13 ("download + import pesado") | pendentes, **e sem `ExecutionType`** |
| Update, instalação de ferramentas, backup/restore → **App** | §13, com o porquê de cada um | cumprido |
| Rename e exclusão pelo Explorer → **App, síncronos**, com lock persistente | §9 | cumprido |
| Três níveis de concorrência (entre Executions, paralelismo interno, processos externos) | §4 | 2 de 3 (§9 desta auditoria) |
| Worker encerra ao ver a App morrer | §2.2, §16 | **não implementado** |
| Flyway só na App; Worker valida versão e recusa | §17, tabela de decisões finais | **não implementado** |
| Reclaim por lease no start do Worker, com política por tipo | §16 | **não implementado** |
| Perda da sessão de lock invalida a posse antes de mutar | §9.6 | **cumprido** — os seis escritores perguntam no ponto de commit (VII.3, item 12) |

Portanto **"ganhar `ExecutionType`" não é decisão**: é parte do trabalho de migrar os sete workloads
que hoje não têm tipo. O que o A8 não resolve para eles é apenas o `dedup_key` — e mesmo isso ele
prescreve ("constante do tipo" para os globais, "caminho canônico" para o rebuild de metadata).

---

## 5. Decisões que continuam realmente abertas

São três, e só a primeira é grande.

1. **Troca de biblioteca (`LibrarySwitchService`) — não classificada pelo A8.** Pausa o watcher,
   espera cancelamentos, **apaga arquivos** do catálogo antigo, troca a `AppSetting` e reconfigura o
   watcher. É controle da aplicação (mexe em configuração e no watcher, ambos da App) e ao mesmo
   tempo trabalho pesado que escreve. Pelo critério que o A8 usou para update/ferramentas/backup —
   "quem controla a aplicação fica na App" — ela fica; pelo critério de "quem escreve em massa vai ao
   Worker", ela vai. **Precisa de decisão explícita**, e ela muda a ordem de migração porque envolve
   o watcher.
2. **`QUARANTINE_CLEANUP`** — ver §7. Há evidência nova que o A8 não tinha.
3. **`THUMBNAIL_REBUILD` sai do A8** — formalmente é uma decisão (remover texto de um documento
   normativo), materialmente não há trabalho: o workload nunca existiu.

Tudo o mais que a primeira auditoria listou como "decisão" era decisão já tomada, ou consequência
mecânica de uma.

---

## 6. Pontos de escrita no filesystem, e a ordem correta do `SelfWrittenPathRegistry`

### A dependência que a primeira auditoria inverteu

O watcher (`ReadDirectoryChangesW`/USN) **fica na App** — decisão do A8, e barata ali. O
`SelfWrittenPathRegistry` é o que impede que uma escrita nossa seja lida como mudança externa e
dispare inventário. Hoje ele é um `ConcurrentHashMap` **dentro do processo que escreve**.

No instante em que o primeiro workload **que escreve na biblioteca** rodar no Worker, o anúncio
acontece na memória do Worker e a checagem na memória da App: o registry deixa de funcionar sem que
nada quebre visivelmente — o sintoma é inventário completo disparado a cada organização.

Logo: **nenhum workload que escreve no filesystem migra antes de o registry ser cross-process.** A
tabela `self_written_path` já existe (V16) e não tem escritor nem leitor.

### Mapa dos pontos de escrita

`SecureFileMove` **não cobre tudo**. Levantados todos os `Files.move/delete/copy/write/newOutputStream/
createFile/createTempFile/createDirectories` de `src/main/java`:

| Ponto | Domínio | O que escreve | Dentro da biblioteca observada? | Anuncia hoje? |
| --- | --- | --- | --- | --- |
| `SecureFileMove` | organization | move (origem **e** destino) | **sim** | **sim** |
| `DefaultExplorerFileSystem` (arquivo e diretório) | media/explorer | delete | **sim** | **sim** |
| `ExplorerRenameService` (pasta) | media/explorer | `Files.move` cru de diretório | **sim** | **não** |
| `EmptyDirectoryCleaner` | organization | remove diretório vazio após organizar | **sim** | **não** |
| `QuarantinePurgeService` (arquivo e pasta `exec-<id>`) | quarantine | delete | **depende** — a raiz é a `AppSetting` `duplicates.trash-folder`, que o usuário pode apontar para dentro da biblioteca | **não** |
| `LibraryCatalogCleanupService` | settings | `deleteIfExists` ao trocar de biblioteca | na biblioteca **antiga** | **não** |
| `ConversionFileNaming` (temp) e `PhotoPerceptualHashService` (temp) | conversion, metadata | cria/apaga no `workspace/temp` | não | n/a |
| `ConversionFilePlacement` | conversion | entrega o convertido via `SecureFileMove` | sim | sim (herdado) |
| Thumbnails, backup/`pg_dump`, cluster embarcado, dataset geográfico, download de update, instalação de ferramentas | vários | workspace e pasta de instalação | não | n/a |
| ffmpeg / ffprobe / exiftool / mediainfo | processos externos | **escrevem só no destino que passamos** — sempre workspace | não | n/a |

Três achados:

- **Quatro pontos escrevem dentro da biblioteca sem anunciar** — rename de pasta, remoção de
  diretório vazio, purga de quarentena (quando a lixeira está dentro) e limpeza da biblioteca antiga.
  Hoje o efeito é limitado (um inventário a mais, no mesmo processo); depois da migração, cada um
  vira ruído cross-process.
- **Nenhum processo externo escreve na biblioteca por conta própria.** Todos recebem o caminho de
  saída e ele é sempre o workspace. Isso simplifica o registry: basta anunciar o `SecureFileMove`
  final, não a atividade do ffmpeg.
- **A regra semântica correta é por evento observável, não por API.** Quem provoca uma mudança que o
  watcher pode ver anuncia — inclusive `Files.delete` de diretório, que hoje ninguém trata.

### Consequência para a ordem

O registry persistente entra **antes** dos itens 3 a 9 da matriz (organização, undo, conversão, dedup,
quarentena) e **depois** de nada — não depende de migração alguma. Ele pode, e deve, ser feito
enquanto os workloads ainda estão na App, o que dá um teste honesto: com App e Worker rodando, uma
escrita anunciada por um processo tem de ser consumida pelo outro.

---

## 7. `QUARANTINE_CLEANUP`: o que é, de fato

Rastreamento completo:

- **Quem cria a `Execution`**: `QuarantinePurgeService.cleanupAbsent()`, via
  `QuarantineOperationLog.startAbsentCleanup(n)` — e só quando há ao menos um ausente ("nothing
  absent: no record will end, so there is no operation to record").
- **Quem dispara**: `QuarantineWebController` — **um clique na tela de quarentena**.
- **Onde executa**: na própria thread HTTP, sincronamente.
- **O que faz**: relê o disco item a item, e remove do catálogo os registros cujo arquivo não está
  mais na quarentena. **Não apaga nada do disco.** Rechecar em vez de confiar na tela é deliberado —
  uma unidade momentaneamente indisponível faria tudo parecer ausente.
- **Locks**: sim. `QuarantinePurgeService` tem dois `operationLockService.acquire(...)`, um na purga e
  outro aqui — e é exatamente esse "×2" que a matriz §9 do A8 conta.
- **Teto**: `MAX_PER_RUN` por passada.

Então **não é registro histórico**: é job com caminho de execução, lock e contadores próprios. A
primeira auditoria errou nisso.

**Por que ainda assim reabro:** o A8 classificou "`QuarantinePurgeService` (×2) → Worker" contando
**sítios de lock**, não operações — e um dos dois é um clique síncrono, sem escrita em disco, com teto,
que termina em milissegundos. É a mesma natureza que o A8 usou para deixar o Explorer na App
("degradar 'clicou → fez' para 'vira job, fica PENDING' seria trocar milissegundos por segundos"). A
purga, que apaga arquivos e roda por timer, é o caso oposto e claramente pertence ao Worker.

**Recomendação (não executada):** manter `QUARANTINE_CLEANUP` como `ExecutionType` — a tela precisa
mostrar a operação e ela não pode ser lida como a purga que apaga —, migrar **a purga** ao Worker e
manter **a limpeza de ausentes** síncrona na App, corrigindo a §9 do A8 para contar operações em vez
de sítios de lock. Isso não mistura "tipo de job" com "tipo de registro": o Explorer também tem tipo e
é síncrono.

---

## 8. `expiredLeases()`: não é código morto

Semanticamente, um contém o outro:

```
UNOWNED  : status='RUNNING' AND (lease_until IS NULL OR lease_until < now)
EXPIRED  : status='RUNNING' AND  lease_until < now
```

`EXPIRED ⊂ UNOWNED`. A diferença é uma linha `RUNNING` **que nunca teve lease** — que só existe porque
14 workloads ainda rodam fora da fila. Quando tudo estiver na fila, `lease_until IS NULL` com
`RUNNING` torna-se impossível e as duas consultas convergem.

O que o código tem hoje é **uma** recuperação: `ExecutionProgressService.markInterruptedExecutions()`,
na App, no `ApplicationReadyEvent`, marcando `INTERRUPTED` tudo que está sem dono e não está vivo
neste processo.

O que o A8 **especifica e não existe** é outra coisa: reclaim no start do Worker, com política por
tipo (§16) —

```
Worker novo, no start: reclaim de execuções cujo lease venceu
   tipo retomável  → PENDING (claim_count preservado)
   tipo mutável    → INTERRUPTED + enfileira RECONCILE da pasta
   CONVERSION      → INTERRUPTED + apaga .part daquele publicId
```

**Esse é o caso que exige `expiredLeases()`, e `unownedExecutions()` não serve para ele:** o Worker
não pode devolver a `PENDING` uma execução que nunca teve lease, porque ela pertence ao motor antigo
da App — reenfileirá-la faria o mesmo trabalho duas vezes, em dois processos, sobre os mesmos
arquivos.

Divisão correta, portanto:

| Consulta | Consumidor certo | Por quê |
| --- | --- | --- |
| `unownedExecutions()` | recuperação de start **da App** | precisa alcançar também as execuções legadas sem lease |
| `expiredLeases()` | reclaim de start **do Worker** | precisa alcançar **só** quem passou pela fila |

**Classificação corrigida:** não é código morto a remover — é a metade não integrada de um mecanismo
que o A8 exige. Vira código morto de verdade **se** a decisão for não implementar o reclaim, o que
contrariaria o §16.

---

## 9. Limites por categoria: intenção arquitetural, não escolha sobre um método

O A8 §4 define **três níveis distintos**, com donos distintos:

| Nível | O que limita | Dono no A8 | No código hoje |
| --- | --- | --- | --- |
| 1. Entre `Execution`s | quantas execuções coexistem, e quais podem coexistir | `ExecutionDispatcher`: limite global + **limites por categoria** + exclusão de caminho | **parcial** — global via `worker.max-concurrent` (N laços do `WorkerLoop`, default 3) e exclusão via advisory locks; **por categoria: nada** |
| 2. Paralelismo interno de uma execução | quantos arquivos em voo dentro de um job | `ProcessingCoordinator` | existe — pool + semáforo de backpressure; usado por inventário, os dois backlogs e o motor de fingerprint |
| 3. Processos externos | quantos ffmpeg/ffprobe simultâneos, por categoria de ferramenta | `ExternalToolGate` | existe — semáforo justo por `ExternalToolCategory` (4 categorias) |

Os níveis 2 e 3 **não substituem** o 1, e a diferença é observável: o `ProcessingCoordinator` limita
arquivos em voo *dentro* de uma execução e o `ExternalToolGate` limita *processos*; nenhum dos dois
sabe quantas `Execution` do mesmo tipo estão rodando.

**Problema específico que só o limite por categoria resolve:** os tipos de pedido do usuário têm
`dedup_key` **nulo** por decisão do A8 ("são pedidos distintos do usuário" — `CONVERSION`,
`DEDUP_DELETE`, `ORGANIZATION`, `UNDO`, `QUARANTINE_*`). Sem dedup, e com caminhos diferentes (logo,
sem conflito de lock), três laços do `WorkerLoop` podem reivindicar **três conversões ao mesmo tempo**.
Cada uma segura arquivo temporário no workspace, memória e uma fatia do `ExternalToolGate`; o gate
segura os processos, mas não impede as três execuções de existirem, competirem e se arrastarem. Hoje
isso não acontece porque `AtomicBoolean running` no `VideoConversionAsyncRunner` permite uma —
**exatamente a garantia que se perde ao migrar**.

`ExecutionJobHandler.concurrencyLimit()` já declara o contrato com default 1 (o `InventoryJobHandler`
o afirma em teste) e o `ExecutionDispatcher` **não o lê**.

**Classificação: NÃO IMPLEMENTADO.** Não é candidato a remoção — remover reabriria a decisão do §4 e
deixaria a migração dos tipos de usuário sem a unicidade que o `AtomicBoolean` dava. É pré-requisito
da migração dos itens 3 a 9 da matriz, não trabalho posterior.

---

## 10. `stillHolds()`: desenho da garantia, não um chamador de conveniência

**A invariante (§9.6):** perder a sessão que sustenta o advisory lock invalida a posse; nada pode
continuar mutando depois disso. Advisory lock de sessão morre com a conexão — silenciosamente. Um
segundo processo pode então adquirir o mesmo caminho enquanto o primeiro ainda escreve.

Estado: `OperationLockService.stillHolds(OperationLock)` existe, consulta `pg_locks` pela própria
conexão de lock, devolve `false` em `SQLException` (testado), **e não tem consumidor de produção**.

### Por que não é regressão observável hoje

Os dois handlers do Worker não mutam a biblioteca (§2). Não há, hoje, mutação a proteger **dentro** do
motor novo. Fora dele, App é um processo só e a perda de conexão derruba o próprio runner. A
invariante está **não garantida**, não violada.

### Onde a verificação precisa acontecer

Critério: **imediatamente antes de cada escrita irreversível, e no mesmo ponto de checagem onde o
cancelamento já é consultado** — nunca por arquivo numa varredura de 145 mil.

| Ponto | Cadência | Ação na perda |
| --- | --- | --- |
| `OrganizationExecutor` / undo | uma vez por lote, no mesmo ponto do `cancel_requested` | aborta antes do próximo move; o que já moveu está íntegro (baseline + verificação) → `INTERRUPTED` + `RECONCILE` da pasta |
| Conversão — **entre o fim do ffmpeg e o `SecureFileMove`** | uma vez por arquivo convertido (custo desprezível ao lado de minutos de ffmpeg) | **não comita**: descarta o temporário, `INTERRUPTED`, sem tocar na biblioteca |
| Dedup delete, restauração e purga de quarentena | por lote | aborta antes do próximo move/delete |
| Explorer rename/delete | **não verificar** | a operação inteira dura milissegundos dentro do escopo do lock; uma consulta dobraria a latência para fechar uma janela que não existe |
| Inventário e reconcile | **não verificar** | não mutam |

### O que fazer quando a posse se perde no meio de um processo externo

Não se interrompe o ffmpeg pela posse: os bytes já escritos estão no **workspace**, não na
biblioteca. A regra é que **a perda de posse fecha o commit, não o cálculo** — deixa terminar,
descarta o resultado, não move nada. É a mesma política que o A8 já dá para "PostgreSQL caiu no meio":
libera os locks, não inicia/não comita, e a recuperação resolve.

### Forma, para não virar chamadas espalhadas

O dispatcher já é dono do `OperationLock`. O desenho é expor **uma** guarda no contexto que o handler
recebe — algo como `assertStillOwned()`, que lança exceção dedicada — e chamá-la nos pontos da tabela.
O handler não conhece `pg_locks`, não decide política, e a lista de pontos de mutação fica auditável
num lugar só. Custo: um round-trip por lote, na conexão que já está aberta.

**Dependência:** isso só passa a ter efeito quando o primeiro workload que muta rodar no Worker —
mas precisa existir **antes**, porque é justamente essa migração que abre a janela.

---

## 11. Parent PID e versão de schema: o que exatamente falta

Ambos são requisitos fechados do A8 (§2.2/§16 e §17/decisões finais). Nenhum dos dois tem uma linha.

### Parent PID

Evidência: não há `--nimbus.app-pid` sendo passado nem lido. O único `ProcessHandle.of(...)` do
projeto é do `PostgresProcessRunner`, sem relação. `ProcessBuilderWorkerLauncher` monta hoje
`--spring.profiles.active=worker`, `--spring.main.web-application-type=none` e
`--spring.flyway.enabled=false`.

O que falta, e o desenho mínimo coerente:

- **Como a App passa** — mais um argumento no mesmo lugar em que os três já existem, com
  `ProcessHandle.current().pid()`. Argumento, não variável de ambiente: fica visível no comando e no
  log, e o `WorkerProperties` não precisa saber dele.
- **Como o Worker observa** — no start, `ProcessHandle.of(appPid)`. **Vazio já significa App morta**
  (morreu entre o spawn e o boot) e o Worker sai imediatamente. Presente: `onExit()` com a sequência
  de prioridade que o A8 fixa — *(1)* parar de reivindicar, *(2)* terminar o lote em curso deixando o
  filesystem consistente, *(3)* matar os ffmpeg filhos, *(4)* sair.
- **Banco indisponível junto** — a App supervisiona o PostgreSQL e pode levá-lo embora. O caminho de
  saída **não pode depender do banco**: se persistir `INTERRUPTED` falhar, sai mesmo assim; o lease
  vence sozinho e o reclaim do §8 resolve no próximo start.
- **Relação com o restart** — nenhuma. O restart é decisão da App (`WorkerSupervisor.wanted`); o
  parent PID é a rede de segurança para quando **não existe** App para decidir. Um Worker que sai por
  morte da App não é reiniciado por ninguém, que é o comportamento correto — a próxima abertura sobe
  um Worker novo.
- **Shutdown ordenado** — não muda: continua sendo a App pedindo `stop()` antes de sair, o que o
  empacotamento exige. O parent PID cobre só a **morte abrupta**, em que esse pedido nunca chega.
- **`app-worker-combined`** — não há segunda JVM, e `appPid` seria o próprio processo: o observador
  **não é registrado**. A decisão fica num ponto só, junto da que já distingue os três papéis
  (`app & !worker` / `worker & !app` / ambos), e não espalhada em `if`.

### Versão de schema

Evidência: nenhuma consulta a `flyway_schema_history` no Worker. Existe uma consulta pronta —
`CatalogSchemaRepository.schemaVersion()`, em `backup/infrastructure/persistence` — que é do domínio
de backup e **não deve ser reusada por importação cruzada**; o Worker precisa da sua, na sua camada.

- **Qual versão o Worker espera** — a versão que **o próprio artefato carrega**, isto é, a maior
  migration em `db/migration` do jar em execução. Não uma constante escrita à mão: constante
  desatualiza em silêncio, e o cenário real é App e Worker de versões diferentes durante uma
  atualização.
- **Como chega ao Worker** — não chega da App: o Worker lê do próprio classpath. Passar por argumento
  criaria a possibilidade de a App mentir sobre a versão do binário do Worker, que é o que se quer
  detectar.
- **Como consulta** — `SELECT version FROM flyway_schema_history WHERE success ORDER BY
  installed_rank DESC LIMIT 1`, exatamente como o backup já faz.
- **Onde no startup** — antes do `WorkerLoop` começar. Hoje `WorkerConfig` sobe o laço no
  `ApplicationReadyEvent`; a validação precisa acontecer **antes desse ponto**, e a garantia de que
  nenhum claim ocorre antes vem de o laço só existir se a validação passar — não de uma flag que o
  laço consulta.
- **Comportamento na incompatibilidade** — Worker mais **velho** que o banco: sai com log claro (a App
  migrou; a supervisão trará um Worker novo com o binário novo). Worker mais **novo** que o banco:
  espera curta e reavalia — a App pode estar no meio do Flyway —, e desiste depois de um limite.
  Nunca migra: Flyway só na App, decisão fechada.

---

## 12. Classificação de risco, corrigida

A primeira auditoria disse "nenhum bug", o que era verdade e escondia o resto.

**A. Regressão ou defeito funcional observável hoje** — nenhum. Nada do que foi verificado se comporta
de forma errada na versão instalada.

**B. Mecanismo implementado e não integrado** — existe, é testado, ninguém consome:

- `stillHolds()` — sem chamador (§10);
- `expiredLeases()` — sem chamador, e sem o reclaim que o consumiria (§8);
- `concurrencyLimit()` — declarado, o dispatcher não lê (§9);
- tabela `self_written_path` — criada na V16, sem escritor nem leitor (§6).

**C. Invariante prometida e não garantida** — o mecanismo não fecha a promessa:

- §9.6, perda de posse antes de mutar — nada verifica (invariante 3);
- §2.2, Worker encerra ao ver a App morrer — nada observa o pai (invariante ausente da lista, mas
  exigida pelo §16);
- §17, Worker recusa schema incompatível — nada valida;
- §16, reclaim por lease com política por tipo — não existe;
- "A App não executa trabalho pesado do Worker" — 14 workloads a executam (invariante 9);
- "Sem segundo modelo de execução" — `@Async` + `AtomicBoolean` é o segundo motor (invariante 13).

**D. Risco que só se materializa na migração** — inerte hoje, bloqueador depois:

- `SelfWrittenPathRegistry` em memória — hoje escritor e watcher são o mesmo processo e os dois
  workloads já migrados não escrevem; **bloqueia a primeira migração de writer** (§6);
- ausência de limite por categoria — hoje `AtomicBoolean` garante unicidade; **perde-se ao migrar**
  (§9);
- quatro pontos de escrita sem anúncio (§6) — hoje custam um inventário extra; depois, ruído
  cross-process;
- `stillHolds` sem consumidor — sem efeito enquanto o Worker não muta.

**E. Código morto** — um item, e só um: `InventoryScanAsyncRunner` (§3). `expiredLeases()` e
`concurrencyLimit()` **saem** desta categoria, onde a primeira auditoria os pôs.

**F. Trabalho simplesmente não executado** — migração dos 14 workloads (7 com tipo, 7 sem), com o
`ExecutionType`, o `dedup_key` e o handler de cada um; e a limpeza do passo 9, que depende de tudo
acima.

---

## 13. Invariantes, reavaliadas

| # | Invariante | Estado | Observação desta passada |
| --- | --- | --- | --- |
| 1 | Uma `Execution` nunca roda em dois dispatchers | garantida **onde a fila é usada** | não alcança os 14 fora da fila |
| 2 | Operações conflitantes nunca coexistem | **garantida** | o lock é usado dentro e fora da fila |
| 2b | Irmãs não se bloqueiam por ancestrais | **garantida** | matriz A–G |
| 3 | Perder o banco invalida a posse antes de mutar | **não garantida** | §10; sem efeito hoje porque o Worker não muta |
| 4 | Row lock nunca aberto durante execução | **garantida** | `reserve` é um statement |
| 5 | Lease é posse, não prazo | garantida **no mecanismo** | falta o reclaim que a consome (§8) |
| 6 | `claim_count` monotônico | **garantida** | guarda atômica testada |
| 7 | PENDING sobrevive a restart | **garantida** | recuperação filtra `RUNNING` |
| 8 | Cancelamento ≠ shutdown | **parcial** | 7 tipos ainda cancelam por memória |
| 9 | App não executa trabalho pesado do Worker | **não garantida** | 14 workloads |
| 10 | Worker não supervisiona PG/Flyway/tray/update | **garantida** | `WorkerProfileCompositionTest` |
| 11 | Explorer rename/delete síncronos | **garantida** | e o rename tem lacuna de anúncio (§6), não de sincronia |
| 12 | Escrita própria não causa rajada | **não garantida entre processos** | §6; inerte só porque os migrados não escrevem |
| 13 | Sem Spring Batch nem segundo modelo | Batch **removido**; segundo modelo **presente** | `@Async` + `AtomicBoolean` |
| 14 | Nenhum ffmpeg órfão no shutdown | **parcial** | o Worker ainda não roda ffmpeg; a App depende dos runners |
| 15 | Falha de Worker não gera laço infinito | garantida **no mecanismo** | |
| 16 | Nada roda entre lock e `claim_count++` | **garantida** | `locksThenCountsTheAttemptThenRuns` |

---

## 14. Ordem de correção, por dependência real

A ordem abaixo confere com a expectativa registrada e muda dois pontos: o registry sobe para **antes**
de qualquer writer, e os limites por categoria entram como pré-requisito da migração, não como
faxina.

**Fase 0 — fechar a própria auditoria.** Este documento. Sem código.

**Fase 1 — decisões abertas (§5).** Troca de biblioteca; `QUARANTINE_CLEANUP`; remoção de
`THUMBNAIL_REBUILD` do A8. Bloqueiam a fase 6 porque definem *o que* migra.

**Fase 2 — código comprovadamente morto.** Apenas `InventoryScanAsyncRunner` e seu teste. Não toca em
`expiredLeases()` nem em `concurrencyLimit()`.

**Fase 3 — mecanismos transversais, todos independentes da migração:**

  1. parent PID (§11) — isolado, sem dependências;
  2. validação de schema (§11) — isolado;
  3. reclaim por lease no start do Worker, consumindo `expiredLeases()` (§8);
  4. guarda de posse (`assertStillOwned`) no contexto do handler (§10) — precisa existir antes do
     primeiro handler que muta;
  5. limite por categoria no `ExecutionDispatcher`, consumindo `concurrencyLimit()` (§9) — idem.

Nada aqui depende de migrar workload; tudo aqui é pré-requisito de migrar.

**Fase 4 — `SelfWrittenPathRegistry` cross-process.** Escrita e leitura em `self_written_path`, mais os
**quatro pontos de escrita que hoje não anunciam** (§6). Provado com App e Worker de verdade: um
processo anuncia, o outro consome, e o watcher não dispara inventário. **É o bloqueador absoluto da
fase 5.**

**Fase 5 — migrar os workloads que escrevem na biblioteca**, do que mais move para o que menos:
organização e undo → conversão → exclusão de duplicados → restauração e purga de quarentena. Cada um
com handler, `dedup_key`, política de retry e o `RECONCILE` de compensação.

**Fase 6 — migrar os workloads pesados que não escrevem**: os dois backlogs, as duas similaridades,
os dois rebuilds e o dataset geográfico. Sete workloads que precisam **ganhar `ExecutionType`** —
trabalho, não decisão (§4). Vêm depois da fase 5 apenas por prioridade; tecnicamente não dependem da
fase 4, o que os torna candidatos a paralelizar se a fase 5 travar.

**Fase 7 — provar o invariante.** Nenhum caminho de processamento fora de fila/claim/lease: um teste
que falhe quando um `@Async` novo aparecer, e não uma varredura manual — foi varredura manual que
deixou passar o `InventoryScanAsyncRunner`.

**Fase 8 — o passo 9 original.** `AsyncConfig` e os três pools, `@Async` obsoletos, runners obsoletos,
`AtomicBoolean running`, `isLive`, `inventoryPending`. Só aqui, porque cada item é a garantia de
unicidade ou de ciclo de vida de algo que ainda não migrou.

**Fase 9 — reconciliar o A8** com o que o código realmente faz: `ExecutionJobHandler`,
`ExecutionEnqueueService`, `WorkerLoop` com N laços, `nimbus-file-manager.worker.supervise`,
`finishReconcile`, e a §13 reescrita para dizer que a remoção depende da migração completa.

---

## 15. Novas divergências desta passada

1. **`InventoryScanAsyncRunner` órfão** — código morto de produção deixado pela migração do
   `INVENTORY` (§3).
2. **Quatro pontos de escrita na biblioteca sem anúncio ao registry** — rename de pasta, remoção de
   diretório vazio, purga de quarentena com lixeira interna, limpeza da biblioteca antiga (§6).
3. **Troca de biblioteca não classificada pelo A8** — workload pesado que escreve e mexe no watcher,
   ausente de todas as tabelas do documento (§5).
4. **`THUMBNAIL_REBUILD` cita workload inexistente** — e junto dele, no §7 e no §10, `PHASH_BACKLOG`,
   `VIDEO_FINGERPRINT_BACKLOG`, `GEO_DATASET_UPDATE`, `METADATA_REBUILD` e `LOCATION_REBUILD`
   aparecem como se fossem `ExecutionType`. Os cinco últimos **serão** tipos (fase 6); o primeiro não
   (§1).
5. **A §9 do A8 conta sítios de lock como se fossem operações** — origem do "`QuarantinePurgeService`
   (×2)" e da classificação equivocada da limpeza de ausentes (§7).
6. **Os dois workloads migrados são exatamente os que não escrevem** — não foi coincidência
   arquitetural, mas explica por que nenhum risco de escrita cross-process se materializou até aqui, e
   por que isso muda no primeiro writer (§2, §12-D).

---
---

# Parte II — Fase 0: reconciliação com a auditoria independente (Codex)

Terceira passada, feita contra `docs/a8-auditoria-independente-codex.md` — auditoria cega, sem leitura
desta. **Nenhum código de produção foi alterado nesta fase.** Onde as duas divergem, quem decide é o
código; onde o código é ambíguo, decide o A8; onde o A8 conflita com `AGENTS.md`, decide `AGENTS.md`.

As seções da Parte I permanecem, inclusive onde esta parte as corrige — o histórico do erro é parte da
auditoria. **Onde houver conflito, prevalece a Parte II.**

## II.1 Tabela de reconciliação

| # | Achado | Claude (Parte I) | Codex | Código real | Conclusão |
| --- | --- | --- | --- | --- | --- |
| 1 | RECONCILE está migrado | migrado | dois fluxos; watcher executa na App | `InventoryWatchService.launchPendingInventory` → `automaticReconcile` → `organizationReconcileService.reconcileAndApply` **inline, na thread do watcher**; `ReconcileExecutionRecorder` grava a `Execution` já `RUNNING`→`FINISHED`, depois do fato | **CODEX CONFIRMADO / CLAUDE REFUTADO** |
| 2 | RECONCILE escreve no filesystem | não escreve | marcado `FS` | `ReconcileApplier` só toca repositórios; `OrganizationReconcileService` só faz `list`/`walkFileTree`/`isHidden`/`exists`; `OrganizationRenameDetectionService` só faz `Files.size` | **CLAUDE CONFIRMADO / CODEX REFUTADO** |
| 3 | `repaired_items` no fluxo do watcher | não observado | não observado | `ReconcileExecutionRecorder` nunca seta `repairedItems`; grava 0 enquanto a mensagem diz que reparou N | **DIVERGÊNCIA NOVA** |
| 4 | `fingerprint_job_run` é segundo motor | não mencionado | segundo engine com lifecycle, recovery, progresso e unicidade | tabela é da **V1, anterior ao A8**; writers `start()`/`finalizeRun()`; recovery `markRunningAsFailed` no `ApplicationReadyEvent`; **progresso e unicidade são `AtomicBoolean`/`AtomicLong` em memória**; sem fila, claim, lease, dedup ou prioridade | **PARCIALMENTE CORRETO** — segundo *registro persistido*, não segundo *motor*; viola a invariante 13 na letra |
| 5 | `markRunningAsFailed` sem filtro de dono | — | — | `UPDATE ... WHERE status = RUNNING`, **sem cláusula de dono**, disparado por dois `@Component` **sem `@Profile`** | **BUG FUNCIONAL ATUAL — ACHADO NOVO** |
| 6 | Backlogs de fingerprint nascem nas duas JVMs | não observado | nascem em App e Worker | `PhashBacklogStartup` e `VideoFingerprintBacklogStartup`: `@Component` **sem profile** | **CODEX CONFIRMADO** |
| 7 | `ConversionWorkspaceCleaner` nas duas JVMs | não observado | perigoso | `implements ApplicationRunner`, **sem profile**; apaga todo arquivo do work folder | **CODEX CONFIRMADO** |
| 8 | `StartupExecutionRecoveryListener` nas duas JVMs | não observado | roda em ambas | `@Component` sem profile; a guarda é `isLive`, que lê um `Set` **local da JVM** | **CODEX CONFIRMADO**, e a consequência é pior que a descrita (item II.2-B) |
| 9 | `self_written_path` sem reader/writer | confirmado | confirmado | 0 call sites | **AMBAS CONFIRMADAS** |
| 10 | Writers sem anúncio ao registry | 4 pontos nomeados | lista genérica | os 4 confirmados; e **nenhum processo externo escreve na biblioteca** — todos recebem destino no workspace | **CLAUDE CONFIRMADO / CODEX PARCIAL** |
| 11 | Rename do Explorer não trava o destino | não observado | trava source, não target | `acquireWithin(..., ORGANIZATION, source)`; `target = parent.resolve(nome)` só passa por `Files.exists` **antes** do lock | **CODEX CONFIRMADO** |
| 12 | Quarentena pelo Explorer não trava o destino | não observado | destination incompleto | `acquireWithin(..., DEDUP_DELETE, target)` só; enquanto `DuplicateDeletionService`, `VideoConversionService` e `QuarantineService.restore` **incluem a raiz da quarentena / o destino** | **CODEX CONFIRMADO** — e o Explorer é o outlier do próprio padrão do projeto |
| 13 | `stillHolds()` sem chamador | confirmado | confirmado | 0 call sites de produção | **AMBAS CONFIRMADAS** |
| 14 | Lease renova por conexão diferente da que sustenta o lock | não observado | renovação pode ocorrer em outra conexão | `OperationLockService` usa conexão dedicada via `DriverManager`; `LeaseRenewer` → `ExecutionQueue.renewLeases` usa **Hikari**, com `WHERE claimed_by = :workerId AND status='RUNNING'` — nada sobre a sessão de lock | **CODEX CONFIRMADO — achado mais forte da rodada** |
| 15 | `BackgroundWorkGate` é local; restore não drena o Worker | não observado | confirmado | dois `volatile boolean` em memória; `WorkerLoop`/`ExecutionDispatcher` **não consultam o gate**; `CatalogBackupService.restoreStarted()` só afeta a JVM da App | **CODEX CONFIRMADO** |
| 16 | Troca de biblioteca não cancela o Worker | decisão aberta de papel | bug entre JVMs | `waitForCancellation()` observa `executionQueryService.active()` (**banco**) mas cancela por `requestAllCancellations()`, que itera o `Set running` **local** → espera algo que nunca cancela → `IllegalStateException` no timeout | **CODEX CONFIRMADO**, e o efeito é **falha da operação**, não apenas "não cancela" |
| 17 | Worker manual executa Flyway | não observado | confirmado | `application-worker.properties` **deliberadamente** não desliga Flyway (comentário explica: vazaria para `app-worker-combined`); quem desliga é o argumento do supervisor | **CODEX CONFIRMADO** — é propriedade do launcher, não do papel |
| 18 | `main` instala tray antes dos profiles | não observado | confirmado | `ApplicationTray.install(...)` sem guarda de papel, antes de `SpringApplication.run` | **CODEX CONFIRMADO** |
| 19 | `main` avalia elevação também no Worker | não observado | confirmado | avalia, mas `UsnElevation.shouldRelaunch` exige `launcher != null` e `jpackage.app-path` **não existe** num `java -jar` | **CODEX PARCIALMENTE CORRETO** — avaliada, inócua por construção |
| 20 | Parent PID ausente | confirmado | confirmado | nenhum `--nimbus.app-pid`, nenhum `ProcessHandle.of(appPid)` | **AMBAS CONFIRMADAS** |
| 21 | Compatibilidade de schema ausente | confirmado | confirmado | nenhuma consulta a `flyway_schema_history` fora do backup | **AMBAS CONFIRMADAS** |
| 22 | Limite por categoria ausente | NÃO IMPLEMENTADO | não existe quota por categoria | `concurrencyLimit()` declarado, `ExecutionDispatcher` não lê | **AMBAS CONFIRMADAS** |
| 23 | `expiredLeases()` | consulta do reclaim não implementado | falta sweep runtime e retomada por tipo | `EXPIRED ⊂ UNOWNED`; nenhum reclaim de Worker existe | **CONVERGEM** |
| 24 | `ExecutionQueue` viola `AGENTS.md` | não levantado | conflito normativo, opção do A8 superada | **8 de 8** repositórios JDBC custom do projeto são consumidos de `application` (timeline, media, thumbnail ×2, geolocation, backup, e os dois do A8) — padrão anterior ao A8 | **PARCIALMENTE CORRETO — DECISÃO NECESSÁRIA**, e não é do A8 (item II.4-1) |
| 25 | `isBusy()` é conservador | não levantado | pode serializar irmãos | `chainOf` inclui ancestrais; **um único chamador**, o watcher, que quer exatamente esse "adia, nunca descarta" | **PARCIALMENTE CORRETO — sem impacto** |
| 26 | `THUMBNAIL_REBUILD` | não existe no código | thumbnails são on-demand, limitação consciente | confirmado: `PhotoThumbnailService.get(...)` por requisição, sem `@Async` | **CONVERGEM** |
| 27 | `QUARANTINE_CLEANUP` | operação real, síncrona, só catálogo; recomenda ficar na App | "síncrono App / não aderente" | confirmado: clique em `QuarantineWebController` → `cleanupAbsent()`, sob lock, com teto, sem escrita em disco | **DEFINIÇÕES DIFERENTES → DECISÃO** |
| 28 | Cancelamento de PENDING | não observado | não funciona | `REQUEST_CANCEL` tem `WHERE ... status = 'RUNNING'` | **CODEX CONFIRMADO** |
| 29 | Restart do Worker sem backoff/desistência | não observado | confirmado | `onExit().thenAccept(this::restartIfStillWanted)` reinicia de imediato, sem contagem nem exit code | **CODEX CONFIRMADO** |
| 30 | `ExecutionEnqueueService` mascara violações | não observado | qualquer `DataIntegrityViolationException` vira duplicata | confirmado no `catch` | **CODEX CONFIRMADO** |
| 31 | Contagem 23 × 33 | 23 workloads | 33 workloads | reconciliam exatamente (item II.3) | **DEFINIÇÕES DIFERENTES** |
| 32 | `InventoryScanAsyncRunner` órfão | achado próprio | achado próprio | 0 chamadores de produção | **AMBAS CONFIRMADAS** |

## II.2 Os dois fluxos que a reconciliação obriga a rever

### A. RECONCILE tem dois modelos de execução, não um

| | Fluxo agendado | Fluxo por evento do watcher |
| --- | --- | --- |
| Dispara | `ReconcileScheduler` (timer, `TIMER`) | `InventoryWatchService.launchPendingInventory` (`FILE_EVENT`) |
| JVM | **Worker** | **App** |
| Cria `Execution` | sim, **PENDING antes** de rodar | sim, **`RUNNING`→`FINISHED` depois** de rodar, e só se reparou algo |
| Fila/claim/lease/dispatcher | sim | **não** |
| Lock | sim (dispatcher **e** o próprio `reconcileAndApply`, reentrante) | sim (só o do `reconcileAndApply`) |
| Muta | **só banco** | **só banco** |
| `repaired_items` | preenchido por `finishReconcile` | **fica 0**, mesmo tendo reparado |

Portanto a Parte I errou ao dizer "RECONCILE migrado". O correto: **o fluxo agendado migrou; o fluxo
reativo continua sendo trabalho pesado executado na App, fora da fila.** A Parte I chegou a listar o
`ReconcileExecutionRecorder` como produtor — sem perceber que ele não enfileira, apenas registra.

Isso muda também o texto da Parte I sobre risco de escrita: continua verdadeiro que **nenhum dos dois
fluxos escreve na biblioteca** (o item 2 da tabela refuta o Codex nesse ponto), então o bloqueio da
Fase 4 permanece pelas mesmas razões — e não por causa do reconcile.

### B. Achado novo: o Worker mata execuções vivas da App

Três beans **sem `@Profile`** rodam no `ApplicationReadyEvent`/`ApplicationRunner` das duas JVMs. Duas
consequências que nenhuma das auditorias enunciou:

1. **`StartupExecutionRecoveryListener` no Worker.** Chama `markInterruptedExecutions()` →
   `unownedExecutions()` = `RUNNING AND (lease_until IS NULL OR ...)`. Toda execução da App
   (organização, conversão, dedup, quarentena) é `RUNNING` **sem lease**. A única guarda é
   `executionCancellationService.isLive(id)`, que consulta um `Set` **local da JVM**. Na JVM do
   Worker, a resposta é sempre "não é minha" → **o Worker marca `INTERRUPTED` uma organização que a
   App está executando neste instante**, com `finishedAt` preenchido.
   *Janela:* toda reinicialização do Worker — e o supervisor reinicia **imediatamente**, sem backoff
   (item 29), a cada `onExit`.
2. **`PhashBacklogStartup` / `VideoFingerprintBacklogStartup` no Worker.** Chamam
   `markRunningAsFailed(...)` — `UPDATE ... WHERE status = RUNNING`, **sem filtro de dono** — e em
   seguida `backlogRunner.start(); backlogRunner.run()`. Ou seja: o Worker marca como `FAILED` o run
   que a App está executando, e **começa um segundo drain do mesmo backlog**, em outra JVM, com dois
   `AtomicBoolean` que não se enxergam. A unicidade do backlog é em memória e não sobrevive à segunda
   JVM.

Ambos são **defeitos funcionais do topology atual**, não riscos futuros. O primeiro é o mais grave:
corrompe o estado exibido de uma operação que continua mexendo em arquivos.

### C. O lease mascara a perda do lock

`OperationLockService` mantém uma conexão dedicada (`DriverManager`, fora do Hikari) por operação; o
advisory lock é dessa sessão e o PostgreSQL o libera no instante em que a conexão cai. O
`LeaseRenewer` renova por **outra** conexão (Hikari), com `WHERE claimed_by = :workerId AND status =
'RUNNING'` — que continua verdadeiro.

Resultado: caída a conexão de lock, o mundo passa a ver *"esta execução tem dono e lease válido"*
enquanto ela **não segura lock nenhum** e segue mutando. Um segundo processo pode adquirir os mesmos
caminhos legitimamente. A invariante 3 não é apenas "não verificada": o mecanismo de lease
**esconde** a violação. Isso é o argumento decisivo para o desenho da §10 e acrescenta um requisito
que a Parte I não tinha: **parar de renovar o lease de uma execução cuja sessão de lock morreu.**

## II.3 Contagem de workloads: as duas estão certas

Não há conflito — são unidades de medida diferentes, e **reconciliam exatamente**:

- **Classificação A — workloads funcionais relevantes para a decisão App/Worker: 23.** Agrupa o que
  decide junto: preview dentro de organização, thumbnail de foto e de vídeo como um caso, backup e
  restore como um, e não conta runners de infraestrutura que nunca foram candidatos ao Worker.
- **Classificação B — unidades de trabalho de runtime relevantes para concorrência, recursos e
  subprocessos: 33.** Conta cada disparador com ciclo de vida próprio.

A diferença é **exatamente 10**, e cada um tem call site real:

| # | Unidade que a classificação B separa | Call site |
| --- | --- | --- |
| 1 | Reconcile por evento do watcher | `InventoryWatchService.automaticReconcile` |
| 2 | Preview de organização | `OrganizationAsyncRunner` (2º `@Async`) |
| 3 | Purga de registros do catálogo | `CatalogFilePurgeScheduler` (`@Profile(APP)`) |
| 4 | Check/download de update | `UpdateCheckScheduler` (`@Profile(APP)`) |
| 5 | Observação/debounce do filesystem | `InventoryWatchService.pollSafely` |
| 6 | Thumbnail de vídeo (separado do de foto) | `VideoThumbnailService` |
| 7 | Delete permanente pelo Explorer (separado do envio à quarentena) | `DefaultExplorerFileSystem.delete` |
| 8 | Limpeza do workspace de conversão | `ConversionWorkspaceCleaner` (**sem profile**) |
| 9 | Bootstrap/controle do PostgreSQL embarcado | `EmbeddedDatabaseBootstrap` / `PostgresProcessRunner` |
| 10 | Restore de backup (separado da criação) | `CatalogBackupAsyncRunner` (2º `@Async`) |

**23 + 10 = 33.** As duas contagens de "pendentes" também são consistentes: 14 na base A; na base B o
Codex conta 31 fora da fila incluindo os 10 deliberadamente App e a infraestrutura.

Dois itens da classificação B eram desconhecidos da Parte I e **têm consequência**:
`ConversionWorkspaceCleaner` (item II.2-B) e `CatalogFilePurgeScheduler` (decisão de papel em aberto,
levantada pelo Codex).

## II.4 Baseline factual consolidada

### A. Fatos estabelecidos

1. Existem **dois motores** para trabalho de fundo: fila/claim/lease/dispatcher (2 fluxos) e
   `@Async`/timer/síncrono (o resto). Há ainda um **terceiro registro persistido**,
   `fingerprint_job_run`, sem fila.
2. **Nenhum dos dois fluxos que rodam no Worker escreve na biblioteca.** Inventário lê; reconcile
   repara catálogo. É o motivo de nenhum incidente de self-write cross-process ter aparecido.
3. **Seis beans nascem nas duas JVMs sem profile**: `PhashBacklogStartup`,
   `VideoFingerprintBacklogStartup`, `ConversionWorkspaceCleaner`, `StartupExecutionRecoveryListener`,
   `DefaultUserInitializer` (benigno — idempotente e a App sobe antes) e `RuntimeBudgetLogger`
   (deliberado). Os handlers (`InventoryJobHandler`, `ReconcileJobHandler`) também são `@Component`
   sem profile, o que é inócuo por não existir dispatcher na App.
4. **Toda coordenação de estado entre App e Worker que não passa por coluna do banco é local**:
   `ExecutionCancellationService.running`, `BackgroundWorkGate`, `SelfWrittenPathRegistry` e os
   `AtomicBoolean` dos runners.
5. **O `main` roda antes dos profiles** e instala tray em qualquer papel; a elevação é avaliada e
   inócua no Worker.
6. **`application-worker.properties` não desliga Flyway** por decisão consciente (vazaria para o
   combined); a garantia vem do argumento do supervisor.
7. A fronteira `application → infrastructure.persistence` é o padrão do projeto inteiro (8/8), não
   uma introdução do A8.

### B. Invariantes realmente garantidas

| # | Invariante | Prova |
| --- | --- | --- |
| 1 | Uma `Execution` não roda em dois dispatchers | claim atômico; teste de dois claimers concorrentes |
| 4 | Row lock do claim é curto | um único `UPDATE ... RETURNING` |
| 5 | Lease é posse, não prazo | `LeaseRenewer` renova em statement único, fora das threads de trabalho |
| 7 | PENDING sobrevive a restart | recuperação filtra `RUNNING`; teste de reserva posterior |
| 16 | Nada roda entre lock e `claim_count++` | ordem do `ExecutionDispatcher`, asseverada em teste |

Todas valem **apenas para os dois fluxos que passam pela fila**.

### C. Invariantes parciais

| # | Invariante | Por que só em parte |
| --- | --- | --- |
| 2 | Caminhos conflitantes se excluem | núcleo correto; **rename e quarentena do Explorer não travam o destino** |
| 2b | Irmãs coexistem | garantida no núcleo; `isBusy` é conservador, sem impacto no único chamador |
| 6 | `claim_count` monotônico | correto onde existe; a maioria dos workloads não passa por ele |
| 8 | Cancelamento ≠ shutdown | `cancel_requested` só alcança `RUNNING`; PENDING não é cancelável; não há sinal administrativo |
| 11 | Explorer sincroniza por lock interprocesso | sincroniza a origem, não o destino |
| 14 | Sem ffmpeg órfão | runners individuais matam seus filhos; o `destroyForcibly` do Worker não alcança a árvore |
| 15 | Falha não vira laço infinito | guarda de `claim_count` correta; **o supervisor reinicia sem backoff nem desistência**, e PENDING esgotado encalha |

### D. Invariantes não garantidas

| # | Invariante | Estado |
| --- | --- | --- |
| 3 | Perder o banco invalida a posse antes de mutar | `stillHolds` sem chamador **e** o lease renovando por outra conexão mascara a perda |
| 9 | App não executa trabalho pesado do Worker | 14 workloads + o reconcile reativo |
| 10 | Worker não executa PostgreSQL/Flyway/tray/update | tray instalado no Worker; Worker manual roda Flyway |
| 12 | Escrita própria não gera rajada, evento externo não some | registry em memória, tabela vazia, 4 writers sem anúncio |
| 13 | Sem segundo modelo persistido | Batch saiu; `fingerprint_job_run` permanece |

### E. Mecanismos criados e não integrados

`stillHolds()` · `expiredLeases()` · `concurrencyLimit()` · tabela `self_written_path` ·
`request_payload` (coluna e transporte existem, nenhum produtor/consumidor) · `priority` (todos os
produtores usam zero) · `Execution.phase`/`claimed_by`/`lease_until` não são limpos no estado terminal.

### F. Bugs funcionais atuais

Ordenados por gravidade, todos **verificados no código** e todos **exclusivos do topology App+Worker**
(nenhum ocorre em `combined`):

1. **Worker marca `INTERRUPTED` execução viva da App** — `StartupExecutionRecoveryListener` sem
   profile + `isLive` local. Dispara a cada restart do Worker.
2. **Backlog de fingerprint roda em duplicidade e um marca o run do outro como `FAILED`** —
   `markRunningAsFailed` sem filtro de dono + startups sem profile.
3. **Troca de biblioteca falha por timeout** quando há execução do Worker ativa — `waitForCancellation`
   observa o banco e cancela pela memória.
4. **`ConversionWorkspaceCleaner` do Worker apaga o temporário de uma conversão viva da App** — janela
   estreita (restart do Worker durante conversão), impacto alto.
5. **Restore concorre com o Worker** — `BackgroundWorkGate` local; o `WorkerLoop` continua reivindicando
   enquanto o catálogo é substituído.
6. **Tray duplicado** — `ApplicationTray.install` no processo Worker.
7. **`repaired_items` fica 0** no reconcile reativo, embora a mensagem afirme o reparo.

### G. Riscos que só aparecem depois de migrar

- `SelfWrittenPathRegistry` em memória → rajada de inventário no primeiro writer no Worker.
- Ausência de limite por categoria → perde-se a unicidade que o `AtomicBoolean` garante hoje.
- `stillHolds` sem consumidor → hoje o Worker não muta; no primeiro writer, a invariante 3 passa a
  valer de verdade.
- Worker manual sem validação de schema → Worker antigo consumindo schema novo durante upgrade.
- Reinício sem backoff + validação de schema que faz o Worker sair = **laço de spawn**; os dois têm de
  entrar juntos.

### H. Decisões realmente abertas

| # | Decisão | Por que é minha e não do código |
| --- | --- | --- |
| 1 | **Fronteira de `ExecutionQueue` × `AGENTS.md`** | tensão literal real, mas **8 de 8** repositórios JDBC custom já fazem isso, muito antes do A8. Criar 8 ports seria a "abstração cerimonial" que o próprio `AGENTS.md` proíbe. Alternativas: (a) registrar a exceção no `AGENTS.md`; (b) criar ports em todos os 8; (c) mover os consumidores para `infrastructure`. **Não é um problema do A8** e não deve bloqueá-lo |
| 2 | **`LibrarySwitchService`** | controle da aplicação (critério que manteve update/tools/backup na App) × escrita em massa (critério que manda ao Worker); hoje **está quebrado nas duas JVMs** de qualquer modo |
| 3 | **`QUARANTINE_CLEANUP`** | o A8 mandou ao Worker contando **sítios de lock**; a operação é um clique síncrono, sem escrita em disco, com teto |
| 4 | **`fingerprint_job_run`** | virar `Execution` (e o que fazer com o histórico existente) ou permanecer exceção declarada |
| 5 | **`CatalogFilePurgeScheduler`** | levantado pelo Codex; o A8 mantém o scheduler mas não diz onde a execução roda |
| 6 | **`THUMBNAIL_REBUILD` sai do A8** | formal; zero trabalho |
| 7 | **Política de retry por causa e destino de poison job** | o A8 tem a tabela de causas (§10), o código não a implementa; falta decidir o estado final de um PENDING que esgotou tentativas |

## II.5 Ordem revista, pelas dependências reais

A ordem da Parte I sobrevive, com **uma inversão obrigatória**: os defeitos de isolamento de papel
(F.1, F.2, F.4, F.6) são **anteriores** aos mecanismos transversais, porque quebram hoje e porque o
próprio trabalho das fases seguintes vai reiniciar o Worker repetidamente.

| Fase | Conteúdo | Depende de |
| --- | --- | --- |
| 0 | esta reconciliação | — |
| 1 | decisões II.4-H | 0 |
| 2 | invariantes transversais: parent PID, schema **+ backoff/desistência juntos**, reclaim por lease, guarda de posse **+ parar a renovação quando a sessão de lock morre**, limite por categoria, cancelamento de PENDING, gate de manutenção cross-process | 1 |
| 3 | isolamento de papel: profiles nos 4 beans, tray fora do Worker, Flyway como propriedade do papel, `isLive`/recovery com dono, `requestAllCancellations` pelo banco | 2 (parte) — mas **F.1/F.2 devem vir primeiro de tudo** |
| 4 | `self_written_path` cross-process + os 4 writers sem anúncio + os escopos source/target do Explorer | 3 |
| 5 | migrar não-writers (backlogs, similaridades, rebuilds, dataset) | 2, 3 |
| 6 | migrar writers (organização, undo, conversão, dedup, quarentena) e o **reconcile reativo** | 4, 5 |
| 7 | prova por teste da arquitetura única | 6 |
| 8 | limpeza do passo 9 | 7 |
| 9/10 | aceitação cross-process e reconciliação final do A8 | 8 |

---
---

# Parte III — Fase 1: decisões arquiteturais

Fechadas contra o código, com o A8 decidindo intenção e o `AGENTS.md` prevalecendo sobre os dois.
**Nenhum código de produção foi alterado nesta fase; nenhum bug foi corrigido.**

## III.0 Correção que esta fase faz na Parte I

`LibraryCatalogCleanupService.clear()` **não apaga arquivos da biblioteca**. Ele faz
`catalogFileRepository.deleteWithinLibrary(...)` — banco — e limpa `workspace/cache/thumbnails`, que
está **fora** da árvore observada e protegido por `ClusterProtection`. A Parte I §6 o listou como
writer da biblioteca sem anúncio ao registry; **está errado e sai da lista**. Os pontos de escrita na
biblioteca sem anúncio passam de quatro para **três**: rename de pasta pelo Explorer,
`EmptyDirectoryCleaner` e purga de quarentena quando a lixeira está configurada dentro da biblioteca.

## III.1 Decisão 1 — fronteira de `ExecutionQueue` × `AGENTS.md`

**Evidência.** Os oito repositórios JDBC custom do projeto são consumidos por `application`:
`TimelineQueryRepository`←`TimelineService`, `MediaContentRepository`←`MediaContentService`,
`PhotoThumbnailRepository`/`VideoThumbnailRepository`←os serviços de thumbnail,
`GeoAdminBoundaryImportRepository`←`GeoJsonBoundaryImporter`,
`CatalogSchemaRepository`←`CatalogBackupService`, e os dois do A8. **Oito de oito**, e seis são
anteriores ao A8.

O `AGENTS.md` carrega as duas regras que colidem: *"a `application` não conhece `infrastructure`"* e
*"os repositórios JDBC custom … são adapters e residem em `<domínio>/infrastructure/persistence`"*.
Carrega também a regra que decide o empate: *"uma interface com um único implementador que apenas
embrulha o framework, sem ponto de variação nem valor de teste, é cerimônia — evitar"*, e a seção
*"Exceções pragmáticas conscientes"*, que já abre exatamente este tipo de exceção para o Spring Data.

**Alternativas.**

| | Custo | Consistência | Veredicto |
| --- | --- | --- | --- |
| (a) Registrar a exceção no `AGENTS.md` | um parágrafo | descreve 8/8 do código existente | **escolhida** |
| (b) Criar oito ports | 8 interfaces de implementador único, sem ponto de variação, em 8 domínios, durante o A8 | é a cerimônia que o próprio documento proíbe | rejeitada |
| (c) Mover os consumidores para `infrastructure` | `TimelineService`, `MediaContentService`, `ExecutionProgressService`… deixariam de ser casos de uso | destrói a camada | rejeitada |

**Recomendação.** (a), com redação **estreita**: a exceção vale **só para persistência** — um
repositório JDBC custom é um port de persistência implementado concretamente, e `application` pode
consumi-lo, como já consome os ports Spring Data que moram em `domain`. **Não** vale para
`infrastructure/rest`, `infrastructure/web`, process runners, glue nativo ou adaptadores HTTP: esses
continuam invisíveis para `application`.

**A questão é do projeto, não do A8.** O A8 herdou o padrão; corrigi-lo aqui seria refatorar seis
domínios alheios no meio de uma migração. **Não bloqueia nenhuma fase.**

**Natureza:** documental. Um parágrafo no `AGENTS.md`, nenhuma mudança de código.

## III.2 Decisão 2 — `LibrarySwitchService`

**Evidência.** Os cinco passos de `switchLibrary`:

| Passo | Natureza | Pode rodar fora da App? |
| --- | --- | --- |
| `inventoryWatchService.pause()` | estado do watcher, em memória | **não** — o watcher é da App |
| `waitForCancellation()` | cancelar tudo e esperar | precisa ser cross-process (hoje não é) |
| `cleanupService.clear(old)` | um `DELETE` em massa + limpar cache do workspace | sim, mas é banco |
| `appSettingService.update(WATCH_FOLDER)` | configuração global | sim |
| `reconfigureAndInventory()` | reconfigura o watcher e enfileira inventário | **não** — watcher |

**Não escreve na biblioteca observada** (III.0). Três dos cinco passos só existem na App. O único
passo pesado é SQL, não varredura.

**Alternativas.** (a) permanece na App; (b) vira `Execution` do Worker — impossível sem expor o
watcher ao Worker, o que violaria a invariante 10; (c) coordenada, delegando o `clear()` ao Worker —
acrescenta tipo, handler, dedup e máquina de estados a **um `DELETE`**, e a App continuaria esperando
o mesmo tempo, agora com uma fila no meio.

**Recomendação: (a) permanece integralmente na App.** O que está quebrado não é o lugar do workload,
é o **mecanismo de cancelamento**: `waitForCancellation()` observa o banco (`executionQueryService
.active()`) e cancela pela memória (`requestAllCancellations()` itera o `Set running` local). O bug
III da Fase 0 desaparece quando a Fase 2 tornar o cancelamento cross-process — **sem tocar nesta
classe**.

**Consequência que vira requisito da Fase 2:** com Worker separado, "esperar acabar" tem de alcançar
**PENDING** também. Hoje `REQUEST_CANCEL` só toca `status='RUNNING'`, então um inventário PENDING
seria reivindicado no meio da troca. O cancelamento cross-process precisa cobrir os dois estados —
isto liga esta decisão à decisão 7.

**Natureza:** documental quanto ao papel; **acrescenta requisito à Fase 2**.

## III.3 Decisão 3 — `QUARANTINE_CLEANUP`

**Evidência.** Clique em `QuarantineWebController` → `QuarantinePurgeService.cleanupAbsent()`: abre
`Execution` só se houver ausentes, relê o disco item a item (para que uma unidade momentaneamente
indisponível não apague registros reais), toma lock por item, tem teto `MAX_PER_RUN` e **não escreve
nada em disco**. O A8 o mandou ao Worker porque a §9 contou **dois sítios de lock** de
`QuarantinePurgeService` como duas operações equivalentes.

**Recomendação: permanece síncrono na App**, mantendo o `ExecutionType` — a tela precisa distingui-lo
da purga que apaga, e a operação continua no histórico. É o mesmo critério que o A8 já fixou para o
Explorer: *"degradar 'clicou → fez' para 'vira job, fica PENDING, espera worker' … é inaceitável
para UX e desnecessário"*.

**`QUARANTINE_PURGE` continua indo ao Worker**: apaga arquivos, roda por timer e ninguém espera.

Ressalva registrada: o teto `MAX_PER_RUN` faz com que uma quarentena com muitos ausentes precise de
mais de um clique. Já é assim hoje; a decisão não muda isso.

**Natureza:** documental. Nenhuma mudança de código — o comportamento atual **é** o decidido. Corrige
a §9 do A8 na fase de reconciliação final.

## III.4 Decisão 4 — `fingerprint_job_run`

**Evidência nova desta fase, e decisiva:** a tabela é **escrita e nunca lida** em produção.

- O progresso da tela vem de `FingerprintBacklogEngine.status()`, que **deriva do catálogo**
  (`countFingerprintedCatalogFiles`, `countPending`, `countExhaustedFailures`) — não da tabela.
- O ETA vem dos `AtomicLong` do `FingerprintJobRunner`, em memória.
- `findFirstByOrderByStartedAtDesc()` tem **zero chamadores**, em produção e em teste.
- Os únicos toques na tabela são `save` no start, `save` no finalize e `markRunningAsFailed` no
  startup.

Portanto não é fonte de progresso nem de histórico exibido, e a unicidade que ela parece dar é, na
verdade, um `AtomicBoolean`.

**Decisão, nas cinco perguntas:**

1. **Execução** passa à `Execution` + fila/claim/lease/dispatcher quando os backlogs migrarem — isso
   o A8 §13 já decidiu, e a Fase 0 confirmou como trabalho pendente.
2. **Progresso** continua **derivado do catálogo** (pendente/pronto/falho), que é a fonte correta e
   sobrevive a crash, somado ao progresso incremental da própria `Execution`
   (`filesFound`/`filesAnalyzed`/`errors`), que substitui os `AtomicLong` e passa a funcionar
   cross-process. O ETA sai de `ProgressMath` sobre esses números, como já sai.
3. **Histórico existente é transportado, não apagado.** Isto **não é decisão em aberto**: o
   `AGENTS.md` já determina que *"mover para outra tabela ou remover uma coluna ou tabela obriga a
   migration a transportar os dados existentes no mesmo arquivo"*. O mapeamento existe inteiro —
   `kind`+`algorithm`→`execution_type`, `status`→`status`, `started_at`/`finished_at`,
   `processed`→`files_analyzed`, `failed`→`errors`, `message`→`status_message`.
4. **A tabela é eliminada** na fase de limpeza, pela mesma migration que transporta as linhas.
5. **Dois lifecycles não coexistem** porque o `DROP` é o que fecha: enquanto os dois existirem
   (durante as migrações), a regra é que `FingerprintJobRunner` só pode ser chamado pelo runner
   antigo, e cada runner antigo sai junto com a migração do seu backlog.

Efeito colateral a registrar: transportadas para `execution`, as execuções históricas de backlog
passam a **aparecer** no histórico funcional (são `FINISHED` de um tipo que o filtro não esconde).
Isso é ganho, não regressão — hoje o usuário não tem onde ver que um backlog rodou.

**Natureza:** altera implementação futura (migração dos backlogs e migration de transporte + `DROP`).

## III.5 Decisão 5 — `CatalogFilePurgeScheduler`

**Evidência.** `@Profile(APP)`, package-private, diário. Lê `CATALOG_MISSING_RETENTION_DAYS` com
fallback `-1` (desligado por padrão, à prova de setting ilegível) e chama
`CatalogFileRetentionService.purgeMissingOlderThan(days)`, que executa **um**
`catalogFileRepository.deleteMissingBefore(cutoff)`. Sem filesystem, sem varredura, sem lock, sem
subprocesso.

**Decisão: App, scheduler com chamada direta, sem `ExecutionType`, sem fila, sem dedup, sem lock, sem
retry.**

Justificativa: não é workload no sentido do A8 — é uma consulta de manutenção. Um `DELETE` sobre
índice, em milissegundos, uma vez por dia. Enfileirá-lo acrescentaria um tipo, um handler, um dedup e
uma linha de histórico diária para um `DELETE`, sem isolar memória, CPU nem subprocesso.

**Interação com inventário/reconcile: não há corrida.** O reconcile *marca* `MISSING` com
`lifecycle_changed_at = agora`; o purge só remove o que está `MISSING` há mais de N dias, então a
janela de retenção é a própria exclusão mútua. Um arquivo que reaparece é recatalogado pelo
inventário. Nenhum lock é necessário.

Ressalva: se um dia ele passar a varrer disco ou apagar arquivo, a classificação muda. Hoje não faz.

**Natureza:** documental. Nenhuma mudança de código.

## III.6 Decisão 6 — `THUMBNAIL_REBUILD`

**Confirmado: não existe.** `thumbnail/application` tem `PhotoThumbnailService`,
`VideoThumbnailService`, `UnsupportedThumbnailException` e `dto`; `get(publicId, requestedWidth)` gera
**uma** miniatura na requisição que a pede; zero `@Async`, zero runner, zero `ExecutionType`.

**Decisão: modelo conceitual obsoleto.** Sai do A8 na reconciliação final. **Nenhuma implementação
será criada.** No mesmo passo, o A8 §7/§10 deve deixar de tratar `PHASH_BACKLOG`,
`VIDEO_FINGERPRINT_BACKLOG`, `GEO_DATASET_UPDATE`, `METADATA_REBUILD` e `LOCATION_REBUILD` como se já
fossem tipos — esses **passarão a existir**, e o texto muda de "é" para "será".

Observação registrada, não decisão: se algum dia a limpeza do cache de thumbnails crescer para
"regerar todas as miniaturas", aí nasce um workload, e ele seria do Worker. Não existe hoje.

**Natureza:** documental.

## III.7 Decisão 7 — retry por causa e poison jobs

**O que o código faz hoje.** `reserve` filtra `claim_count < maxClaims`; `countAttempt` incrementa
**depois** dos locks, com guarda atômica; `release` devolve a `PENDING` com
`available_at = now + backoff` e **não incrementa**; lock não obtido → `release` (bate com o A8);
**qualquer `RuntimeException` do handler → `fail()` → `ERROR` terminal** (não bate: trata banco
indisponível igual a ffmpeg com código de erro). Não há reclaim, não há classificação por causa, e
`REQUEST_CANCEL` só alcança `RUNNING`.

**Política fechada, a implementar na Fase 2:**

| Causa | Como o dispatcher reconhece | `claim_count` | Estado seguinte | Backoff |
| --- | --- | --- | --- | --- |
| Lock não obtido | `OperationLockException` | **não incrementa** | `PENDING` | `lock-backoff` + jitter |
| PostgreSQL indisponível | exceção de acesso a recurso/conexão | **não incrementa** | `PENDING` se conseguir escrever; senão deixa o lease vencer e o reclaim resolve | exponencial |
| Perda da sessão de lock | guarda de posse | já incrementado | `INTERRUPTED` + enfileira `RECONCILE` da pasta | — |
| Timeout de processo externo | exceção de timeout do runner | incrementado | `PENDING` **uma vez**; na segunda, `ERROR` | fixo |
| Processo externo com código de erro | exit code ≠ 0 | incrementado | `ERROR` | — |
| Entrada/payload inválido | `IllegalArgumentException` do handler | incrementado | `ERROR` | — |
| Arquivo sumiu | ausência detectada pelo handler | incrementado | **não é falha**: conta como *skipped*, execução termina `FINISHED`/`FINISHED_WITH_ERRORS` | — |
| Integridade (`MoveIntegrityException`) | exceção própria | incrementado | `ERROR` | — |
| Cancelamento | `cancel_requested` | incrementado | `CANCELLED` | — |
| Schema incompatível | validação de start do Worker | **nada é reivindicado** | Worker sai; a fila não muda | backoff do supervisor |
| Crash do Worker | lease vence | já contado no claim | reclaim: retomável→`PENDING`; mutável→`INTERRUPTED`+`RECONCILE`; `CONVERSION`→`INTERRUPTED`+apaga o `.part` | — |
| `claim_count` esgotado | `countAttempt` afeta 0 linhas | — | `ERROR` (poison esgotado) | — |

**Regras estruturais que fecham as perguntas em aberto:**

1. **`claim_count` só cresce depois de os locks estarem em mãos** — já é assim, e é o que impede
   disputa de lock de gastar o orçamento de tentativas.
2. **Transitório × permanente é decidido por tipo de exceção, no dispatcher.** O handler não decide
   política, e `catch (RuntimeException) → ERROR` deixa de ser a regra única.
3. **Um `PENDING` que esgotou tentativas não pode existir.** Hoje é inalcançável (só se chega ao
   máximo estando `RUNNING`), mas **o reclaim da Fase 2 o tornaria alcançável**. Regra: o reclaim que
   devolveria a `PENDING` uma execução com `claim_count >= max` **termina-a como `ERROR`** em vez de
   devolvê-la. É isso que impede o poison job de ficar elegível para sempre *e* de morrer em silêncio
   — ele termina visível na tela.
4. **Prioridade.** Uma execução liberada conserva `priority`, e o envelhecimento usa `created_at`, que
   o release não altera: um item repetidamente disputado **ganha** prioridade com o tempo, que é o
   comportamento desejado.
5. **Dedup.** Ver o achado III.8 — o release precisa tratar o caso em que já existe um `PENDING` com
   a mesma chave.

**Natureza:** altera implementação futura (Fase 2).

## III.8 Achado novo desta fase: o release pode violar o índice de dedup

**Não corrigido.** Sequência inteiramente alcançável hoje:

1. `ReconcileScheduler` enfileira `RECONCILE(k)` → `PENDING(k)`;
2. o Worker reivindica → a linha vira `RUNNING(k)` (sai de `ux_execution_pending_dedup`, entra em
   `ux_execution_running_dedup`);
3. o scheduler dispara de novo e enfileira outro `RECONCILE(k)` → **permitido por desenho**
   (1 PENDING + 1 RUNNING);
4. o dispatcher não consegue o lock (o watcher segura a árvore) e chama `release`, que faz
   `SET status = 'PENDING'`;
5. passam a existir **dois `PENDING(k)`** → violação de `ux_execution_pending_dedup`.

A exceção nasce dentro do `catch (OperationLockException)` de `runClaimed` e sobe até o `WorkerLoop`.

**Classificação:** bug funcional latente do motor novo, **de baixa frequência** (exige disputa de lock
coincidindo com o intervalo do scheduler) e **sem perda de dado** — a execução fica `RUNNING` com
lease, e o reclaim da Fase 2 a resolveria.

**Não muda nenhuma das sete decisões.** Entra como item da Fase 2, com a regra: **o release de uma
execução cujo `dedup_key` já tem um `PENDING` termina-a como `CANCELLED`** — já existe um pedido
idêntico esperando, que é a resposta honesta.

## III.9 Natureza das sete decisões

| # | Decisão | Documental | Altera implementação futura |
| --- | --- | --- | --- |
| 1 | Fronteira `ExecutionQueue` | **sim** (parágrafo no `AGENTS.md`) | não |
| 2 | `LibrarySwitchService` fica na App | sim | **sim** — acrescenta "cancelar PENDING" ao escopo da Fase 2 |
| 3 | `QUARANTINE_CLEANUP` fica síncrono | **sim** (corrige o A8) | não |
| 4 | `fingerprint_job_run` | não | **sim** — migração dos backlogs + migration de transporte e `DROP` |
| 5 | `CatalogFilePurgeScheduler` fica na App | **sim** | não |
| 6 | `THUMBNAIL_REBUILD` sai do A8 | **sim** | não |
| 7 | Retry por causa e poison jobs | não | **sim** — Fase 2 |

## III.10 Plano da Fase 1.5 — estabilização da topologia atual (não executado)

Escopo fechado em **quatro** defeitos de isolamento de papel. Cada um foi conferido contra as sete
decisões: **nenhum depende de qualquer uma delas**, e nenhum toca fluxo que será redesenhado.

| # | Defeito | Menor correção coerente | Independente das decisões porque… |
| --- | --- | --- | --- |
| 1 | `StartupExecutionRecoveryListener` no Worker marca `INTERRUPTED` execução viva da App | **`@Profile(APP)`** na classe | a recuperação de linhas sem lease é da App, que é quem as criou e quem sabe responder `isLive`. O reclaim **do Worker**, por lease, é mecanismo novo da Fase 2 e não substitui este |
| 2 | `PhashBacklogStartup` e `VideoFingerprintBacklogStartup` nascem nas duas JVMs | **`@Profile(APP)`** nas duas classes | restaura o comportamento de uma JVM só, que é onde os backlogs rodam hoje. A decisão 4 os move ao Worker mais tarde **substituindo** estes beans por produtores — não reaproveitando-os |
| 3 | `ConversionWorkspaceCleaner` no Worker apaga temporário de conversão viva da App | **`@Profile(APP)`** na classe | a limpeza tem de rodar onde a conversão roda, e hoje é a App. Quando `CONVERSION` migrar, o cleaner acompanha e ganha a varredura por `publicId` que o A8 §14 descreve — isso é da fase de migração, não desta |
| 4 | `ApplicationTray` instalada também no Worker | decidir o papel **antes do Spring**, a partir do argumento que o launcher já passa | `ProcessBuilderWorkerLauncher` passa exatamente `--spring.profiles.active=worker`; não há nada a decidir arquiteturalmente |

**Sobre o item 2:** com o profile, `markRunningAsFailed` volta a ter um único executor e o defeito
desaparece **sem alterar a query**. Acrescentar filtro de dono seria corrigir duas vezes o mesmo
problema, e a query some junto com a tabela (decisão 4).

**Sobre o item 4, a armadilha:** a regra **não pode** ser "o argumento contém `worker`" —
`app-worker-combined` contém a palavra e **é** App. A regra correta é *instalar a bandeja salvo quando
os profiles ativos forem exatamente `worker`*, que é precisamente o que o launcher passa. Por ser
código pré-Spring e por ter essa armadilha, a decisão pertence a um helper testável, não a um `if`
dentro do `main`.

**Fora do escopo desta fase, registrado:** `DefaultUserInitializer` também não tem profile e roda nas
duas JVMs. É benigno — é idempotente (`if (count() > 0) return`) e a App fica pronta antes de o Worker
ser lançado —, então não entra sem sua autorização. Fica anotado para a fase de isolamento.

**Explicitamente fora da Fase 1.5**, por decisão sua e confirmado aqui: a troca de biblioteca (o
defeito é do cancelamento, e a Fase 2 o resolve — decisão 2), o restore concorrendo com o Worker (o
gate de manutenção é mecanismo da Fase 2) e o `repaired_items` do reconcile reativo (o fluxo é
removido quando o reconcile reativo entrar na fila).

## III.11 Ordem das fases, revista

A ordem que você propôs **sobrevive à Fase 1**. Confrontei-a com as dependências reais e não há
posição a mudar; há três escopos a completar e uma amarração a registrar.

| Fase | Conteúdo | Ajuste vindo da Fase 1 |
| --- | --- | --- |
| 0 | auditoria e reconciliação | concluída |
| 1 | decisões arquiteturais | **esta** |
| 1.5 | quatro defeitos de isolamento | escopo fechado em III.10 |
| 2 | mecanismos transversais | **+ cancelamento de `PENDING`** (decisão 2); **+ política de retry por causa** (decisão 7); **+ tratamento do release contra o índice de dedup** (III.8) |
| 3 | `SelfWrittenPathRegistry` cross-process | **três** writers sem anúncio, não quatro (III.0) |
| 4 | workloads que escrevem na biblioteca | inalterada |
| 5 | workloads pesados que não escrevem | **inclui a migração dos dois backlogs**, que é o que aposenta o `FingerprintJobRunner` (decisão 4) |
| 6 | eliminar fluxos paralelos restantes | **inclui o reconcile reativo** do watcher |
| 7 | provar por testes/invariantes | inalterada |
| 8 | limpeza final | **+ migration que transporta `fingerprint_job_run` para `execution` e a dropa** (decisão 4) |
| 9 | reconciliar o A8 | **+ `THUMBNAIL_REBUILD` (6), §9 do A8 (3), tipos "é"→"será" (6)**; e o parágrafo do `AGENTS.md` (1) |

**Por que 4 antes de 5 se mantém.** As duas ordens satisfazem as dependências: os não-writers não
dependem da Fase 3, os writers dependem. Trocá-las seria preferência, não dependência — e a Fase 3 se
prova por teste cross-process com uma escrita anunciada pelo Worker, sem precisar de um workload
migrado. **Fica como você definiu.**

**Amarrações bloqueadoras, dentro da Fase 2:**

1. **Validação de schema e backoff do supervisor entram juntos.** Um Worker que sai por schema
   incompatível somado a um supervisor que reinicia imediatamente (`onExit().thenAccept(...)`, sem
   contagem nem exit code) é um laço de spawn. Nenhum dos dois pode entrar sozinho.
2. **A guarda de posse exige ligar `OperationLock` a `executionId`.** Hoje o `LeaseRenewer` guarda
   apenas ids e renova por conexão do Hikari; para "parar de renovar quando a sessão de lock morreu",
   ele precisa saber qual sessão sustenta qual execução. É restrição de desenho, não item separado.
3. **Cancelamento cross-process precisa alcançar `PENDING`** antes que a troca de biblioteca volte a
   funcionar (decisão 2).

---
---

# Parte IV — Fase 1.5: estabilização da topologia atual

Executada. Escopo exatamente o fechado em III.10, mais o `DefaultUserInitializer` e o parágrafo do
`AGENTS.md`, ambos autorizados no gate da Fase 1. **Nada além disso foi tocado.**

## IV.1 O que mudou

| # | Item | Mudança | Efeito |
| --- | --- | --- | --- |
| A | `StartupExecutionRecoveryListener` | `@Profile(APP)` | o Worker não pode mais marcar `INTERRUPTED` execução viva da App |
| B | `PhashBacklogStartup`, `VideoFingerprintBacklogStartup` | `@Profile(APP)` | um só drain, e ninguém marca `FAILED` o run da outra JVM |
| C | `ConversionWorkspaceCleaner` | `@Profile(APP)` | o Worker não apaga o temporário de uma conversão viva |
| D | `ApplicationTray` | guarda em `main` por `StartupRole.isStandaloneWorker(args)` | Worker isolado não instala bandeja; App e combined instalam |
| E | `DefaultUserInitializer` | `@Profile(APP)` | endurecimento de fronteira, não correção de defeito |
| F | `AGENTS.md` / `AGENTS.en.md` | exceção estreita de persistência (decisão 1) | fecha a tensão normativa sem port cerimonial |

**Nenhuma query foi alterada.** `markRunningAsFailed` continua sem filtro de dono: com um único
executor o defeito desaparece, e a query some junto com a tabela quando a decisão 4 for executada.
Corrigir os dois seria consertar o mesmo problema duas vezes.

## IV.2 `StartupRole`: a decisão que acontece antes do Spring

A bandeja é instalada em `main`, antes de existir `Environment`, então nenhuma asserção sobre beans
alcança essa decisão. `shared/application/StartupRole` responde a mesma pergunta que
`EmbeddedDatabaseBootstrap` faz ao ambiente resolvido — **`worker & !app`** — lendo
`--spring.profiles.active` (com o `System.getProperty` de mesmo nome como segunda fonte, e o argumento
vencendo, como no Spring).

Três propriedades do desenho, cada uma por um motivo:

- **Casa por nome, nunca por substring.** `app-worker-combined` contém a palavra "worker" e **não** é
  um. Um `contains` teria tirado a bandeja de quem roda os dois papéis no IDE.
- **Um grupo de profiles não é expandido fora do Spring**, então `app-worker-combined` chega aqui como
  um nome opaco — e a resposta que ele recebe, "não é worker isolado", é a correta.
- **Erra para o lado seguro.** Um profile desconhecido deixa a bandeja instalada, que é visível e
  inofensivo; o inverso tiraria em silêncio a única coisa que esta aplicação mostra.

## IV.3 Como cada item foi provado

O contrato desta fase pede prova, não a existência do mecanismo. A prova de que uma JVM não pode fazer
algo é que o bean não existe no contexto daquele papel — e é isso que os três testes de composição
passam a afirmar, um a um, por classe.

| Prova | Onde |
| --- | --- |
| Worker isolado não tem os cinco beans | `WorkerProfileCompositionTest`: `doesNotRecoverExecutionsItCannotAnswerFor`, `startsNoFingerprintBacklog`, `sweepsNoConversionWorkspace`, `provisionsNoAccounts` |
| Worker isolado é reconhecido como tal antes do Spring | `WorkerProfileCompositionTest.isRecognisedAsAWorkerBeforeSpringStarts` |
| App mantém os cinco | `AppProfileCompositionTest.keepsTheStartupWorkThatBelongsToIt` |
| App não é confundida com Worker antes do Spring | `AppProfileCompositionTest.isNotRecognisedAsAWorkerBeforeSpringStarts` |
| Combined mantém os cinco | `CombinedProfileIntegrationTest.keepsEverythingThatBelongsToTheApplicationRole` |
| Combined não cria segunda JVM | `CombinedProfileIntegrationTest.startsNoSecondProcess` — por **profile** (`app & !worker` é falso), não pela propriedade que a suíte usa |
| Combined não é classificado como Worker isolado | `CombinedProfileIntegrationTest.isNotTakenForAStandaloneWorkerBeforeSpringStarts` |
| A regra de papel em si | `StartupRoleTest`, 12 casos, incluindo o combined, ambos os papéis nomeados juntos, caixa alta, espaços, propriedade × argumento e último argumento vencendo |

Os cinco testes unitários que já existiam para essas classes (`ConversionWorkspaceCleanerTest`,
`PhashBacklogStartupTest`, `VideoFingerprintBacklogStartupTest`,
`StartupExecutionRecoveryListenerTest`, `DefaultUserInitializerTest`) constroem os objetos
diretamente, sem contexto Spring — nenhum foi tocado, removido ou enfraquecido pelo `@Profile`.

## IV.4 O que esta fase deliberadamente **não** fez

`LibrarySwitchService`; restore concorrendo com o Worker; `repaired_items` do reconcile reativo;
parent PID; validação de schema; retry/poison; cancelamento de `PENDING`; dedup no release;
`stillHolds`; posse do lease; `self_written_path`; migração de workload; remoção do
`fingerprint_job_run`; `InventoryScanAsyncRunner`.

Observação registrada e **não** executada: `SpringApplication.setHeadless(false)` continua sendo
chamado também no Worker. Sem bandeja instalada nada o usa, então é status quo inofensivo — mas
pertence à mesma decisão de papel e cabe na fase de isolamento, não aqui.

## IV.5 Estado dos defeitos da Fase 0

| # | Defeito | Depois desta fase |
| --- | --- | --- |
| 1 | Worker marca `INTERRUPTED` execução viva da App | **corrigido** |
| 2 | Backlog duplicado e `FAILED` cruzado | **corrigido** |
| 3 | Troca de biblioteca falha por timeout | aberto — Fase 2 (cancelamento cross-process) |
| 4 | Cleaner apaga temporário de conversão viva | **corrigido** |
| 5 | Restore concorre com o Worker | aberto — Fase 2 (gate de manutenção) |
| 6 | Bandeja duplicada | **corrigido** |
| 7 | `repaired_items` zerado no reconcile reativo | aberto — some quando o fluxo entrar na fila |

---
---

# Parte V — Fase 2: mecanismos transversais do motor

Executada em sete fatias, na ordem do contrato. **Nenhum workload migrado, nenhum
`SelfWrittenPathRegistry` cross-process, nenhuma limpeza de legado, A8 principal intocado.**

## V.1 O que cada fatia entregou

### 2A — lifecycle App ↔ Worker

A App passa o próprio pid (`--nimbus-file-manager.worker.parent-pid`); `ParentProcessWatch`
(`worker & !app`) observa e encerra o Worker quando a App some — o caso que o shutdown ordenado nunca
cobre, porque um kill não chega a pedir nada. O `combined` não cria observador: seria um processo
esperando por si mesmo.

A saída não depende do banco. `WorkerStandDown` para de reivindicar **antes** de sair; o encerramento
é o port `WorkerProcessExit`, cujo adapter faz `System.exit` com watchdog que dá `halt` se os hooks
travarem — que é exatamente o caso de a App ter levado o PostgreSQL junto.

`SchemaCompatibility` lê a versão esperada **das migrations do próprio artefato**, nunca de uma
constante. Banco atrás → espera; à frente → recusa na hora; mudo → espera; esgotado → recusa. A
garantia de **zero claim** é estrutural: `WorkerConfig` só inicia os laços se o check passar, então
não existe caminho por onde reivindicar.

`WorkerRestartPolicy` separa morte após funcionamento normal (substitui na hora, como antes) de falha
rápida de startup (1 s, 2 s, 4 s… teto de 60 s, desiste após 8 seguidas). Sem ela, um Worker que sai
por schema incompatível viraria laço de spawn — a dependência bloqueadora que a Fase 0 identificou.

### 2B — posse: advisory lock × lease

`ExecutionOwnership` amarra o que nunca esteve amarrado: `executionId`, a sessão que sustenta os
locks, e a renovação. O `LeaseRenewer` guarda a **posse** em vez do id e **descarta** da renovação
quem perdeu a sessão — era aí que o lease mascarava a perda. Acesso à conexão de lock é serializado
no próprio `ExecutionOwnership`, porque a thread de trabalho e a do renovador chegam nela.

A guarda vai ao handler pelo terceiro parâmetro de `handle(...)`, e `ExecutionJobHandler` documenta
onde chamá-la: entre lotes e imediatamente antes de cada escrita irreversível. Os dois handlers de
hoje não a usam e o Javadoc diz por quê — leem a árvore e escrevem catálogo. **A guarda fecha o
commit, não o cálculo**: um encode que terminou no workspace custou tempo; o que não pode acontecer é
ele entrar na biblioteca por um processo que perdeu o direito de escrever ali.

O dispatcher confirma a posse antes de entregar ao handler, e traduz `OwnershipLostException` em
`INTERRUPTED` — nunca `ERROR`: nada deu errado com o trabalho, o chão saiu de baixo dele.

### 2C — reclaim, retry por causa e poison job

`expiredLeases()` ganhou seu consumidor: `ExecutionReclaim`, no start do Worker, **antes** do primeiro
claim. Não usa `unownedExecutions()`, e há teste afirmando isso — a consulta larga varreria as
execuções do motor antigo, que nunca tiveram lease e estão rodando na App neste instante.

Política por tipo via `ExecutionJobHandler.resumable()`, default `false` (a resposta que não corrompe
nada): retomável volta a `PENDING`, mutável termina `INTERRUPTED`, tipo sem handler é tratado como
mutável.

`RetryPolicy` classifica pela hierarquia do Spring, não por string: transitório volta à fila com
backoff, o resto termina `ERROR`. E o poison job encerra **visível** em vez de voltar invisível para
uma fila que filtra pelo orçamento de tentativas.

### 2D — limite por categoria

`CategoryConcurrency` lê `concurrencyLimit()` e filtra **os tipos perguntados à fila**. Checar antes
de reivindicar é a decisão: um worker que reservasse e depois esperasse vaga estaria segurando linha
reivindicada com lease correndo, que é o oposto do que o limite existe para fazer. Um tipo cheio não
trava os outros.

É o terceiro nível, e nenhum dos outros dois o substitui: `ProcessingCoordinator` limita arquivos
dentro de uma execução, `ExternalToolGate` limita processos externos, e nenhum sabe quantas
`Execution` do mesmo tipo existem. Dedup também não: os pedidos do usuário têm `dedup_key` nula por
decisão, e duas conversões de dois vídeos são dois jobs — o que não podem é ser simultâneas.

### 2E — cancelamento cross-process e quiescência

`requestAllCancellations()` deixou de iterar memória e escreve no banco, alcançando `RUNNING` (flag) e
`PENDING` (encerrado). Isso fecha o **bug 3 da Fase 0** sem mover `LibrarySwitchService`.

A quiescência é uma **janela de manutenção** sustentada por advisory lock, não uma flag em linha. A
razão é o modo de falha: se a App morrer no meio da manutenção, a sessão cai e o trabalho de fundo
volta sozinho; uma flag em tabela continuaria dizendo "pausado" para um produto que ninguém está
rodando, e o fundo ficaria parado até alguém notar. Foi por isso que **não** usei `app_setting`: o
`AppSettingService` cacheia por JVM e só invalida na escrita local — um flag escrito pela App jamais
seria visto pelo Worker, que é a mesma classe de bug que esta fase existe para corrigir.

### 2F — release × dedup, e a distinção que você exigiu

O achado da Fase 1: `RUNNING(k)` devolvida a `PENDING` enquanto já existe `PENDING(k)` viola
`ux_execution_pending_dedup`, de dentro do caminho que estava se recuperando de outra coisa.

`RELEASE` e `REQUEUE` ganharam guarda `NOT EXISTS`; quando a devolução é recusada, o chamador pergunta
`hasWaitingDuplicate` para distinguir "a linha mudou de dono" de "existe sucessora".

**Sobre a restrição do gate: `CANCELLED` não foi usado, e não precisa ser.** O produto já tem
`REJECTED`, que significa exatamente "o sistema não executou isto" — status próprio, rótulo próprio em
`ExecutionLabels`, badge próprio nos templates. Um cancelamento pedido pelo usuário continua
`CANCELLED`; uma supersessão técnica é `REJECTED` com a mensagem `backend.execution.superseded`. Quem
abre o histórico para perguntar "meu cancelamento funcionou?" nunca encontra este no lugar daquele —
a distinção é de **status**, não só de texto, e sobrevive a qualquer contagem por status.

## V.2 Invariantes, reavaliadas ao fim da Fase 2

| # | Invariante | Antes | Agora | Evidência |
| --- | --- | --- | --- | --- |
| 3 | Perder o banco invalida a posse antes de mutar | **não garantida** | **garantida no motor novo** | `OwnershipLossIntegrationTest` com PostgreSQL real: sessão morta → posse falsa → lease **não** renovado; dispatcher traduz em `INTERRUPTED` |
| 5 | Lease é posse, não prazo | garantida no mecanismo | **garantida** | a renovação passou a depender da posse, e há reclaim consumindo o lease vencido |
| 8 | Cancelamento ≠ shutdown | parcial | **parcial, melhor** | cancelamento agora é cross-process e alcança `PENDING`; sinal administrativo de shutdown continua ausente |
| 10 | Worker não executa Flyway | **não garantida** | **garantida como propriedade do papel** | o Worker valida o schema e recusa; deixou de depender do argumento do launcher |
| 15 | Falha não vira laço infinito | parcial | **garantida** | backoff + desistência no supervisor; poison job termina visível; `PENDING` esgotado impossível |
| 1, 4, 6, 7, 16 | fila, claim, contagem | garantidas | **inalteradas** | seguem valendo só para o que passa pela fila |
| 9 | App não executa trabalho pesado do Worker | não garantida | **inalterada** | 14 workloads seguem na App — é a Fase 4/5 |
| 12 | Escrita própria não gera rajada | não garantida | **inalterada** | Fase 3 |
| 13 | Sem segundo modelo persistido | não garantida | **inalterada** | `fingerprint_job_run` sai na limpeza |

**O que a Fase 2 não fechou, e não podia:** as invariantes 9, 12 e 13 dependem de migrar workloads. A
composição cross-process de "schema incompatível → Worker sai → supervisor desiste" está provada por
partes (política pura + processo real + composição de beans), **não** como um Worker real recusando um
banco real — isso pertence à aceitação da Fase 9.

## V.3 Achado novo desta fase, encontrado pelo build

**`RuntimeWorkerProcessExit` podia matar a JVM da suíte.** Um processo de teste hospeda contextos de
worker às dúzias sem ser um worker: não tem supervisor, ninguém observa sua saída, e um contexto que
decidisse ir embora encerraria a execução que o estava exercitando. Aconteceu — o log traz
`Surefire is going to kill self fork JVM ... 30 seconds after System.exit(0)`, e o gatilho foi o
próprio mecanismo novo: o check de schema demorou a ser respondido, esgotou as tentativas e mandou o
processo sair.

Corrigido pondo o encerramento real atrás da **mesma propriedade** que já decide se esta JVM participa
do ciclo de dois processos (`nimbus-file-manager.worker.supervise`). Onde ela está desligada,
`NoOpWorkerProcessExit` responde: a decisão continua sendo tomada, registrada e parando os claims —
só não termina um processo que não é um worker.

Dois efeitos colaterais, ambos resolvidos como o `AGENTS.md` manda e não com teste artificial:

- **SpotBugs** acusou `DM_EXIT`. Encerrar o processo é o propósito inteiro da classe e a razão de ela
  existir separada, então a exclusão está **presa a essa classe** em `spotbugs-exclude.xml`, com o
  motivo escrito ao lado. Em qualquer outro lugar, derrubar a JVM seria o defeito que o detector
  descreve.
- **Cobertura de classe** caiu de 100% porque a classe deixou de ser carregada nos testes. Anotada com
  `@CoverageGenerated`, cujo argumento é exatamente isto: o que ela faz é encerrar esta JVM, e um teste
  não pode deixar isso acontecer.

## V.4 Medição

Build limpo (`clean verify`) mais análise: **2899 testes, 0 falhas, 0 erros, 10 pulados**;
**SpotBugs `BugInstance size is 0`**; **Sonar com 0 issues abertas**, análise `SUCCESS`.

| Métrica | Piso do README | Antes da Fase 2 | Agora |
| --- | --- | --- | --- |
| Instrução | 98,49% | 98,44% | 98,29% |
| Branch | 92,43% | 92,40% | 92,38% |
| Linha | 98,02% | 97,93% | 97,72% |
| Método | 98,92% | 98,86% | 98,68% |
| Classe | 100,00% | 100,00% | **100,00%** |

O piso continua não cumprido — e continua sendo o desencontro que a Fase 1.5 já havia declarado, vindo
do trabalho do A8 ainda não commitado. **O piso não foi alterado.**

A queda desta fase é explicada e não é resíduo novo escondido: a fase acrescentou onze classes de
produção, e o descoberto se concentra em três coisas nomeáveis — o construtor privado anti-instanciação
dos holders (`WorkerConstants`, `WorkerExitCodes`, `SchemaVersions`, `RetryPolicy`,
`WorkerRestartPolicy`, `ExecutionLockKeys`), que o `AGENTS.md` já nomeia como resíduo aceito e cuja
cobertura exigiria reflection que a mesma regra proíbe; os ramos de log de `SchemaCompatibility` que
só ocorrem em tentativas repetidas; e `NoOpWorkerProcessExit`, que existe justamente para não fazer
nada.

**Cobertura devida, e paga:** `requeue`, `requestCancelOfEverything`, `hasWaitingDuplicate` e a guarda
de dedup do `release` ganharam testes de integração contra PostgreSQL real — seis casos novos no
`ExecutionQueueIntegrationTest`. Nenhum teste foi removido ou enfraquecido; nenhum teste artificial
foi escrito.

---
---

# Parte VI — Fase 3: `SelfWrittenPathRegistry` cross-process

Bloqueadora da migração dos writers, e é o que ela fecha: uma escrita anunciada por qualquer processo
passa a ser reconhecida pelo watcher da App.

## VI.1 Revalidação antes de alterar

**Os três writers da auditoria estão confirmados no código**, e por uma razão específica que só aparece
lendo o watcher: em `PhysicalTreeWatcher`, um **DELETE é reportado sem checar se era diretório** — o
comentário diz o porquê, "the path is already gone, so it cannot be inspected". Logo:

- rename de **pasta** pelo Explorer → o nome antigo chega ao watcher;
- `EmptyDirectoryCleaner` → a pasta removida chega;
- purga de quarentena → o arquivo e a pasta `exec-<id>` chegam, quando a lixeira está dentro da
  biblioteca.

Criação de diretório **não** precisa de anúncio: em CREATE/MODIFY o watcher filtra diretórios antes.

**Busca dirigida por um quarto writer: não há.** Todo o resto que escreve vai para o workspace
(temporários de conversão e de hash perceptual, thumbnails, backup, cluster, dataset, update,
ferramentas) ou é o `LibraryCatalogCleanupService`, que a Parte III já corrigiu — ele apaga linhas do
catálogo e o cache de thumbnails do workspace, nunca a biblioteca.

Achado a favor do desenho novo: `SecureFileMove.rollback` move de volta sem anunciar. Com anúncio de
**janela** isso passou a estar certo de graça — os dois extremos continuam anunciados desde o `move`.

## VI.2 O desenho, e por que não é o óbvio

O A8 §10.2 já havia decidido, e a decisão é contra-intuitiva o bastante para merecer ser repetida: o
anúncio é **consultado, não consumido**. Nada de `DELETE ... RETURNING`.

A razão está no watcher, não na tabela. Uma escrita produz várias notificações — nome, tamanho e
última gravação são reportados separadamente, e o parser guarda só o caminho — e uma escrita que dura
minutos as espalha por polls sucessivos. Consumir a primeira deixava **todas as seguintes parecendo
alheias**, que é exatamente a rajada de inventários completos que o registry existe para evitar.

O que isso troca, dito por inteiro: uma alteração **externa** num arquivo *enquanto* o produto escreve
esse mesmo arquivo é suprimida. Exige alguém editar exatamente o arquivo em escrita naquele instante, e
o reconcile posterior o alcança. Fora da janela, nada é escondido — e há teste afirmando isso.

Implementação: tabela `self_written_path` (já existente na V16, **sem migration nova**), repositório
JDBC custom, e o registry passando a delegar. **Sem cache na frente** — um "ninguém anunciou isto"
desatualizado é precisamente a resposta que não pode errar, porque é a que dispara inventário. A
pergunta é feita **uma vez por poll**, em lote, e não uma por caminho.

`execution_id` continua nulo: nenhum writer roda sob `Execution` no Worker ainda. A ancoragem por
execução é o ponto de encaixe da Fase 4, e está aqui declarada como não integrada.

## VI.3 Escopos de lock do Explorer

Duas lacunas da Fase 0, fechadas com o modelo de locks existente e sem transformar o Explorer em
`Execution`:

- **rename** passou a tomar `source` **e** `target`. A verificação de que o destino estava livre era
  feita antes de existir lock que o mantivesse livre.
- **envio à quarentena** passou a tomar o arquivo **e** a raiz da quarentena — que é o que
  `DuplicateDeletionService`, `VideoConversionService` e `QuarantineService.restore` já faziam. O
  Explorer era o único fora do padrão.

Continua síncrono, em milissegundos, sem `assertStillOwned`, e sem ancestral novo: os dois caminhos já
compartilham o mesmo pai, então a cadeia de ancestrais não cresce e irmãs seguem coexistindo.

## VI.4 Prova cross-process

`SelfWrittenPathCrossProcessIntegrationTest`, contra PostgreSQL real: dois `SelfWrittenPathRegistry`
construídos à mão, **sem nenhum objeto entre eles** — não há singleton, cache ou mapa por onde o teste
pudesse passar por acidente. Cobre caminho normal, os dois extremos de um move, diretório, notificação
repetida, instância que nasceu depois do anúncio, expiração, e o caso que não pode ser sacrificado:
mudança que ninguém anunciou continua visível.

Uma segunda JVM acrescentaria o sistema operacional ao quadro e nada à pergunta: o que precisa
atravessar é uma linha, e uma linha atravessa igual para outro objeto ou outro processo.

## VI.5 Teste substituído, e por quê

`SelfWrittenPathRegistryTest.consumesTheRecordSoASecondChangeToTheSamePathIsReported` afirmava o
consumo único. **Não foi enfraquecido: foi substituído**, porque a regra que ele afirmava é a que o
A8 §10.2 decidiu trocar, com o defeito medido do lado da troca. No lugar entraram sete casos que
afirmam a regra nova, incluindo o reconhecimento repetido, a renovação do teto e a expiração.

## VI.6 O que a prova é, e o que ela não é

O teste chama-se `SelfWrittenPathCrossInstanceIntegrationTest`, e o nome é literal. **São dois
objetos numa JVM, não dois processos.** O que ele prova com força é o que estava em dúvida: duas
instâncias construídas à mão, sem nenhum objeto entre elas — sem singleton, sem cache, sem mapa — se
comunicam **exclusivamente pela persistência compartilhada**.

O passo daí para dois processos é **argumento, não experimento**, e se apoia em não haver memória
compartilhada a perder: o registry não guarda estado estático, toda resposta é uma consulta, e a única
coisa que qualquer lado lê é uma linha. Uma segunda JVM repetiria as mesmas asserções com um sistema
operacional no meio e não exercitaria nenhum caminho novo do código. Onde a fronteira de processo
**acrescenta** propriedade — um pid, uma saída, um handle — este projeto sobe processo de verdade,
como fazem os testes de ciclo de vida do Worker. Aqui ela não acrescenta, e por isso não foi criada.

## VI.7 Verificações do gate

**TTL e crescimento da tabela.** A limpeza roda em `announce`, antes de cada inserção
(`deleteExpired(now - TTL)`) — que é onde o mapa também a fazia. O tamanho fica limitado ao que foi
anunciado dentro de uma janela, não ao tempo que o produto está no ar. **Concorrência não cria falso
negativo**: o que a limpeza remove é exatamente o que a consulta já recusa por idade
(`announced_at > :notBefore` com o mesmo teto), então uma remoção concorrente nunca apaga linha que a
consulta aceitaria. Afirmado em `sweepsWhatHasExpiredWithoutTouchingWhatIsStillLive`. Registro honesto:
se nada anunciar por muito tempo, linhas vencidas permanecem na tabela até o próximo anúncio —
inofensivas, porque a resposta é filtrada por idade.

**Índices.** A consulta usa `path_key IN (...) AND announced_at > ?`; `path_key` é **PRIMARY KEY**,
logo índice único, e o `IN` é varredura por índice. A limpeza usa `announced_at <= ?`, coberta por
`ix_self_written_path_announced`, que a V16 já criou. **Nenhum índice novo é necessário** e nenhum foi
criado. O volume é o de uma janela de cinco minutos de escritas.

**Evento externo não é suprimido indevidamente.** Três afirmações explícitas:
`keepsRecognisingTheSameWriteOnEveryLaterPoll` (durante a janela, repetições do próprio Nimbus são
reconhecidas), `stopsRecognisingAWriteOnceItsCeilingHasPassed` (depois do teto, o mesmo caminho volta
a ser externo) e `doesNotHideAChangeNobodyAnnounced` (caminho nunca anunciado continua externo). A
limitação consciente — alteração externa no mesmo caminho **durante** a janela — permanece registrada
em VI.2 e é alcançada pelo reconcile.

**Poluição do registry.** Os três writers desta fase anunciam apenas caminhos da biblioteca: rename do
Explorer e `EmptyDirectoryCleaner` operam nela por construção. A purga de quarentena anuncia a raiz
configurada, que **pode** estar fora — a linha é então inútil, porém limitada ao número de itens
purgados e expira sozinha; guardá-la exigiria dar ao serviço de purga conhecimento da pasta observada,
o que seria acoplamento pior que a linha. Mesmo caso, herdado e não introduzido aqui: `SecureFileMove`
anuncia os dois extremos, e numa conversão a origem é o workspace. Nenhum caminho de
workspace/cache/temporário é anunciado por si.

**`execution_id` permanece nulo.** Nenhum writer roda sob `Execution` no Worker, então não há
associação a fazer, e nenhuma foi inventada. A ancoragem por execução — e a remoção das entradas
quando a execução termina, com a margem de dois polls que o A8 §10.2 descreve — é **pendência
explícita da Fase 4**, a ser feita junto com o primeiro writer migrado.

## IV.6 Medição

Build limpo (`clean verify`) mais análise: **2823 testes, 0 falhas, 0 erros, 10 pulados**;
**SpotBugs `BugInstance size is 0`**; **Sonar com 0 issues abertas**, análise `SUCCESS`.

| Métrica | Piso do README | Última medição registrada antes desta fase | Agora |
| --- | --- | --- | --- |
| Instrução | 98,49% | 98,47% | 98,44% |
| Branch | 92,43% | 92,40% | **92,40%** |
| Linha | 98,02% | 97,97% | 97,93% |
| Método | 98,92% | 98,89% | 98,86% |
| Classe | 100,00% | 100,00% | **100,00%** |

**O piso já não era cumprido quando esta fase começou.** A própria medição anterior gravada no README
— 2800 testes, 98,47/92,40/97,97/98,89 — está abaixo do piso em quatro das cinco métricas. O
desencontro nasceu no trabalho do A8 ainda não commitado, não aqui.

O que **esta** fase contribui é identificável e pequeno:

- `StartupRole` tem **um** membro descoberto, o construtor privado anti-instanciação — o mesmo idioma
  de `NimbusProfiles` e `UsnElevation`, e exatamente o resíduo que o `AGENTS.md` nomeia como aceito.
  Cobri-lo exigiria instanciar por reflection, que a mesma regra proíbe. Um método em ~3 mil ≈ 0,03
  ponto, que é a diferença medida em *método*.
- Os dois branches que a classe nova tinha descobertos foram **eliminados**, e da forma que o
  `AGENTS.md` prefere: a guarda `argument != null` era inalcançável (o array vem de `main`, e
  `UsnElevation.attempted` já não a tem) e **saiu**; o filtro de nome vazio ganhou o caso real que o
  exercita, uma vírgula sobrando na lista de profiles.
- As cinco anotações `@Profile` não acrescentam instrução alguma.

O restante é a oscilação que o `AGENTS.md` documenta, e ela foi observada **nesta árvore**: duas
execuções desta fase, com o mesmo código a menos de uma guarda removida, deram linha 97,95 → 97,93 e
método 98,89 → 98,86.

**O piso não foi alterado.** Baixá-lo exige o procedimento de *Recalcular o piso* — colher primeiro o
que é honesto em qualquer ponto do projeto, classificar o resíduo linha a linha e registrar sua
natureza —, que é trabalho de verdade e não cabe numa fase de estabilização com escopo fechado em seis
itens. Fica como pendência declarada, para decisão.

---

# Parte VII — Fase 4: os workloads que escrevem na biblioteca

A Fase 4 tirou da App todo workload que **move, cria ou apaga arquivo do usuário** e o pôs a rodar no
Worker, pela fila. O critério de pronto não foi "o mecanismo existe": foi o caminho executável real
usar o motor novo **e** o caminho antigo não poder mais fazer o mesmo trabalho em paralelo. Onde havia
um `@Async` que escrevia, ele saiu no mesmo passo em que o handler entrou.

## VII.1 O que cada fatia entregou

| Fatia | Workload | O que passou a existir | O que deixou de existir |
| --- | --- | --- | --- |
| 4A | `ORGANIZATION` (execução) | `OrganizationLauncherService`, `OrganizationJobHandler`, `OrganizationExecutePayload`, `OrganizationMetadataRebuild` | a execução real dentro do `OrganizationAsyncRunner` (sobrou o preview) |
| 4B | `UNDO` | `OrganizationUndoLauncherService`, `OrganizationUndoJobHandler`, `OrganizationUndoPayload` | resposta síncrona do undo (`OrganizationUndoResponse`, painel da tela de detalhe) |
| 4C | `CONVERSION` | `ConversionLauncherService`, `ConversionJobHandler`, `ConversionExecutePayload`, `ConversionProgressService`, `conversion_item_result` (V20), `execution.current_item_percent` (V19) | `VideoConversionAsyncRunner`, `ConversionProgressCallback` |
| 4D | `DEDUP_DELETE` | `DuplicateDeletionLauncherService`, `DuplicateDeletionJobHandler`, `DuplicateDeletePayload`, `DuplicateDeletionProgressService` | `DuplicateDeletionAsyncRunner`, `DeletionProgressCallback` |
| 4E | `QUARANTINE_RESTORE` (lote) e `QUARANTINE_PURGE` | `QuarantineLauncherService`, os dois handlers, `QuarantineProgressService`, `QuarantineRetentionPolicy` | execução do restore em lote e da purga dentro da thread HTTP / do scheduler; `QuarantineDeleteResponse` |

O progresso de dois níveis da conversão foi **preservado**, e é o único caso em que a fase acrescentou
coluna: `current_item_percent` guarda o quanto do item corrente já saiu, com nome por *item* e não por
arquivo ou frame, porque o dado não é do ffmpeg — é de qualquer trabalho longo sobre uma coisa só. As
outras duas informações que viviam em `Atomic*` não precisaram de coluna: o arquivo corrente já é o
`statusMessage`, e o ETA se deriva de `startedAt` com os contadores.

## VII.2 As duas reclassificações do A8

**1. Preview da organização — permanece na App, por ora.** O A8 §9/§13 manda "organização e preview"
para o Worker, e a fase migrou apenas a execução. O preview **não escreve nada**: ele produz um plano
de até centenas de milhares de itens que só a tela que o pediu lê, e esse plano mora na memória do
processo. Levá-lo ao Worker exigiria dar ao plano uma representação cross-process — tabela,
serialização no workspace — que é decisão de produto e não adaptação mecânica. Ele é um **leitor
pesado**, e onde os leitores pesados rodam é a pergunta da fase seguinte. Fica **fora do gate 4F**,
que é o gate dos escritores.

**2. `QUARANTINE_RESTORE → Worker` era amplo demais.** A classificação contava um `ExecutionType`, não
uma operação. Sob esse tipo existem duas coisas diferentes:

- **restore em lote** — o usuário seleciona cartões e manda restaurar; o que volta é um relatório, e a
  operação sobrevive à requisição. **Migrado.**
- **restore unitário** — o usuário abre um item e responde a uma pergunta (colisão de nome, pasta de
  origem sumida) escolhendo renomear, pular ou apontar outra pasta. O que volta não é relatório, é a
  próxima pergunta. **Não se conversa com uma linha esperando na fila**: permanece síncrono na App,
  com os mesmos locks, o mesmo `SecureFileMove` e a mesma `Execution` de sempre.

Pela mesma régua, a **limpeza de ausentes** (`QUARANTINE_CLEANUP`) segue síncrona na App: ela não
apaga nada do disco — reconcilia registros cujo arquivo já não está lá — e tem teto por passagem. E a
**purga** (`QUARANTINE_PURGE`), tanto a que uma pessoa dispara quanto a passagem diária, **vai para o
Worker**: é a operação mais irreversível do produto, escreve — apaga — arquivos do usuário em lote e
sobrevive à requisição que a pediu.

### Onde cada operação da quarentena ficou

| Operação | Onde executa | Por quê |
| --- | --- | --- |
| Restore em lote (`restore-selected`) | **Worker** | escreve na biblioteca, em lote, e sobrevive à requisição |
| Restore unitário (diálogo de um item) | **App, síncrono** | é uma conversa: o que volta é a próxima pergunta, não um relatório |
| Purga — selecionada e passagem diária | **Worker** | apaga arquivos do usuário para sempre, em lote |
| Limpeza de ausentes (`cleanup-absent`) | **App, síncrono** | não toca no disco: reconcilia registros, com teto por passagem |

**Esta tabela é o estado de hoje, não um veredito permanente.** As quatro linhas foram decididas pelo
que cada operação faz *agora* — se escreve, se é lote, se é uma conversa com quem clicou — e a régua
desta fase era estreita de propósito: os escritores em lote. A fronteira App × Worker ainda tem uma
pergunta aberta que esta fase não respondeu (onde rodam os **leitores pesados**, incluindo o preview
da organização), e é numa revisão arquitetural dessa fronteira que estas classificações devem ser
revisitadas — inclusive as duas que ficaram na App. O que **não** muda por revisão é a invariante: o
que escreve na biblioteca em lote roda sob execução, lock e posse, onde quer que rode.

## VII.3 Gate 4F — as vinte verificações dos escritores

Levantado a partir dos `@Async`, dos `*Runner`, dos schedulers, dos controllers que disparam
processamento e de **todos** os `Files.*` de escrita, e não a partir do `ExecutionType` — que é
exatamente o erro que a reclassificação acima corrigiu.

Os seis escritores no Worker: `ORGANIZATION` (execução), `UNDO`, `CONVERSION`, `DEDUP_DELETE`,
`QUARANTINE_RESTORE` (lote), `QUARANTINE_PURGE`.

| # | Verificação | Como está garantida | Prova |
| --- | --- | --- | --- |
| 1 | Nenhum segundo motor sobrevive | os dois runners que restavam saíram (`VideoConversionAsyncRunner`, `DuplicateDeletionAsyncRunner`) e o `OrganizationAsyncRunner` ficou só com o preview (`dryRun=true`); a quarentena deixou de escrever na thread HTTP e na do scheduler | varredura de `@Async`: nenhum dos 14 métodos restantes escreve na biblioteca |
| 2 | O produtor só enfileira | cada escritor tem um `*LauncherService` que monta a linha `PENDING` e devolve `ExecutionResponse` | `queuesTheTreeAsColumnsAndTheVideosAsPayload`, `queuesTheQuarantineRootAsColumnsAndTheItemsAsPayload`, `queuesTheQuarantineRootAsColumnsAndTheFilesAsPayload` |
| 3 | Payload versionado, versão desconhecida recusada | `schemaVersion` no envelope + `@JsonIgnoreProperties(ignoreUnknown = true)`; o handler compara e lança | `refusesAPayloadWrittenInAShapeItDoesNotKnow` nos cinco handlers de escrita |
| 4 | Repetição legítima não vira duplicata | escritor não carrega `dedupKey`: dois pedidos iguais são dois trabalhos, e a recusa por duplicidade seria mentira | `queuesTheQuarantineRootAsColumnsAndTheItemsAsPayload` afirma `dedupKey` nulo; `raisesWhenTheQueueRefusesARequestThatCannotBeADuplicate` |
| 5 | Um handler por tipo | `ExecutionDispatcher` indexa `List<ExecutionJobHandler>` por `type()` | `takesNothingItHasNoHandlerFor`, `runsNothingWhenNoHandlerAnswersForTheType` |
| 6 | Um escritor de cada tipo por vez | `concurrencyLimit()` fica no default `1` nos seis | `allowsOnlyOneRestoreAtATime`, `allowsOnlyOnePurgeAtATime`, `allowsOnlyOneDeletionAtATime`, equivalentes nos demais |
| 7 | Escritor abandonado não recomeça do zero | `resumable()` fica no default `false` nos seis | `refusesToBeRerunFromTheStartAfterBeingAbandoned` nos cinco handlers de escrita |
| 8 | Ninguém roda sem a tentativa contada | `reserve` → `acquireFor` → `countAttempt` → handler, nessa ordem | `locksThenCountsTheAttemptThenRuns`, `runsNothingWhenTheAttemptCannotBeCounted` |
| 9 | Limite por categoria antes do claim | `categoryConcurrency.typesWithCapacity()` filtra o `reserve`, e `tryEnter` devolve o que perdeu a vaga na corrida | `ExecutionDispatcherTest` + `CategoryConcurrencyTest` |
| 10 | As duas pontas travadas | `pathsOf(claimed)` trava `sourcePath` **e** `targetPath` antes de qualquer trabalho | `handsTheExecutionBackWithoutSpendingAnAttemptWhenTheTreeIsBusy` |
| 11 | Lock que só o handler conhece devolve à fila | `OperationLockException` vinda do handler → `handBack`, sem gastar tentativa | `handsTheExecutionBackWhenTheHandlerCannotTakeThePathsItAlsoNeeds`, `undoLocksTheOriginalRestorePathsNotOnlyTheExecutionRoot` |
| 12 | Posse confirmada no ponto de commit | `assertStillOwned()` imediatamente antes de cada escrita irreversível, não no início do laço — direto nos quatro primeiros, e pelo `ExecutionStopReason` nos dois da quarentena | `OrganizationExecutor.ensureStillOwned`, `OrganizationUndoService.runUndo`, `ConversionCommitService.commit`, `DuplicateDeletionService.deleteEach`, `ExecutionStopReason.of` |
| 13 | Posse perdida termina como `INTERRUPTED` | o trabalho não falhou: os locks sob ele foram embora, e culpar o trabalho seria mentira | `stopsBeforeTheNextFileWhenTheLocksUnderItAreGone` (×3), `placesNothingInTheLibraryWhenTheLocksAreGone`, `stopsAsInterruptedWhenItNoLongerOwnsThePaths`, `stopsAsInterruptedWhenItNoLongerOwnsTheQuarantine`, `endsAsInterruptedWhenTheHandlerFindsItsLocksGone` |
| 14 | Cancelar funciona entre processos | o pedido mora na linha `execution`, não num mapa da JVM que clicou; lido por item, com cache de 500 ms | `ExecutionCancellationServiceTest`, e o `isCancelled` por item nos seis |
| 15 | Cancelado termina como `CANCELLED` com o que de fato aconteceu | contadores são locais do laço, então um run cortado reporta quanto andou | `executeShouldStopAndMarkCancelledWhenCancellationIsRequestedMidLoop`, `stopsBetweenFilesWhenTheReversalIsCancelled`, `stopsBetweenFilesWhenTheDeletionIsCancelled`, `stopsWhereItIsWhenSomebodyCancelsTheBatch`, `stopsBeforeTheNextFileWhenSomebodyCancelsThePurge`, `stopClosesTheRowWithHowFarItGotAndWhyItEnded` |
| 16 | Auto-escrita ancorada na execução | `SecureFileMove`, `EmptyDirectoryCleaner`, a purga e a entrada em quarentena anunciam com `executionId`; sem id só sobram rename e exclusão pelo Explorer, que são síncronos e curtos por decisão | varredura de `announce(`: nenhum escritor do Worker anuncia sem id |
| 17 | Operação longa não depende do TTL fixo | a consulta aceita a linha enquanto a execução estiver `RUNNING` com lease vivo, e a limpeza não a varre | `keepsRecognisingAWriteWhoseExecutionStillHoldsItsPaths`, `stopsRecognisingItOnceTheExecutionsLeaseHasLapsed`, `doesNotSweepAnEntryWhoseExecutionStillHoldsItsPaths`, `fallsBackToTheCeilingOnceTheExecutionHasEnded` |
| 18 | Mídia do usuário só se move por `SecureFileMove` | baseline SHA-256, verificação byte a byte e rollback; os `Files.delete` diretos são deleções deliberadas (purga, pasta vazia, Explorer) | varredura de `Files.move`: os únicos sobre mídia estão dentro do `SecureFileMove` |
| 19 | Relatório e progresso vêm da linha | nenhuma tela lê memória de processo: conversão tem `conversion_item_result`, os demais leem contadores da `execution` | `ConversionProgressServiceTest`, `DuplicateDeletionProgressServiceTest`, `QuarantineProgressServiceTest` |
| 20 | Mensagem é código, não texto | o Worker não tem requisição e portanto não tem idioma; a linha guarda `StatusMessage.coded` e quem lê localiza | `BackendI18nTest.duplicateDeletionStoresItsOutcomeAsACodeRatherThanText`, `quarantineBatchesStoreTheirOutcomeAsACodeRatherThanText` |

## VII.4 P1 — retry e reclaim do `UNDO` depois de uma reversão parcial

Pedido explicitamente no fechamento do 4B, e agora provado em vez de deduzido.

Não existe retry automático: `OrganizationUndoJobHandler` não é resumível, então o `ExecutionReclaim`
encerra a execução abandonada como `INTERRUPTED` e **não** a devolve à fila — a segunda tentativa é um
clique novo, que cria outra `Execution` do tipo `UNDO`.

O checkpoint por arquivo é o próprio `MovementStatus.UNDONE`, gravado na **mesma transação** que
atualiza catálogo e localização: ou o arquivo voltou e o banco sabe, ou nada disso aconteceu. Na
tentativa seguinte, `undoOne` começa por `if (movement.getStatus() == UNDONE) return SKIPPED` — é aí
que mora a idempotência. Mesmo com status velho, as duas guardas físicas pegam o caso
(`!Files.exists(target)` → `SOURCE_NOT_FOUND`; `Files.exists(source)` → `TARGET_EXISTS`), e nenhuma
delas move nada.

Afirmado em `aSecondAttemptSkipsWhatTheFirstAlreadyPutBack`: dois movimentos, um já `UNDONE` e outro
ainda `MOVED`; a segunda passagem devolve `undone=1`, `skipped=1`, `errors=0`, não toca no arquivo que
já havia voltado e não escreve segundo movimento para ele.

A janela estreita que sobra — o processo morrer **entre** o `SecureFileMove` e o commit — vira **um
erro** na tentativa seguinte, não uma segunda movimentação, e a divergência de catálogo é fechada pelo
`RECONCILE` das duas pontas que o reclaim passou a enfileirar.

## VII.5 Achados fechados durante a fase

Sete coisas que não estavam no plano e teriam ficado quebradas:

1. **Reclaim sem compensação.** Uma execução não resumível abandonada era encerrada e pronto: o que
   ela havia movido ficava divergente do catálogo sem que nada fosse reconciliar. `ExecutionReclaim`
   passou a enfileirar `RECONCILE` das duas pontas do que a execução estava tocando.
2. **`OperationLockException` do handler matava a execução.** O dispatcher só tratava a exceção do
   lock que ele mesmo tomava; um handler que precisa de caminhos além do par de colunas — o undo lê
   seus movimentos e trava onde cada arquivo estava — falhava como erro. Agora volta para a fila.
3. **Regressão de i18n, introduzida pela própria migração.** Os runners removidos carregavam o locale
   da requisição para a thread de fundo. Com o trabalho no Worker não há requisição, e
   `StatusMessage.raw(message(...))` passou a resolver no idioma padrão do Worker. Fechado com
   `*Messages` por domínio e `StatusMessage.coded`, localizado na leitura.
4. **Auto-escrita sem execução em dois pontos.** A purga de quarentena e o `EmptyDirectoryCleaner`
   anunciavam sem `executionId`, e voltariam a depender do teto fixo de cinco minutos num trabalho que
   dura horas. Ambos passaram a anunciar sob a execução.
5. **Um NPE na primeira leitura de toda conversão.** `current_item_percent` é nula até o ffmpeg
   reportar pela primeira vez — que é o estado de todo lote nos seus primeiros segundos, e a tela
   consulta desde o primeiro. O `NumberUtils.toInt(long)` desembrulhava a nula e a tela respondia
   500. Encontrado ao cobrir o caminho, não em execução.
6. **Duas chaves de JS fora do catálogo.** `js.quarantine.restoreQueued` e `.purgeQueued` entraram
   nos bundles e não no `i18n-messages.html`; apareceriam cruas na tela. O teste que existe para
   isso (`JavaScriptI18nTest`) pegou.
7. **A passagem diária deixaria uma linha por dia.** Com a purga enfileirada, um dia sem nada vencido
   escreveria uma execução dizendo "0 expurgados" e enterraria as linhas que registram deleção real.
   `QuarantineRetentionPolicy.hasOverdue` responde **se** há algo vencido antes de enfileirar; **o que**
   está vencido continua sendo decidido quando a purga roda.

## VII.6 Conversão: GPU, queda para CPU e a ausência deliberada de `-threads`

Levantado sobre o comando real que o projeto monta (`VideoConversionCommandBuilder`), e não sobre o
que o A8 supôs.

**O que existe hoje.** O encoder sai de `HardwareEncoderProbe.hardwareEncoder()` quando o perfil de
qualidade pede hardware, e **cai para `VideoEncoder.SOFTWARE` (libx265)** quando a máquina não tem
placa utilizável. A decodificação é sempre por software: não há `-hwaccel` na entrada. E **não existe
`-threads` em lugar nenhum do projeto**, nem `-x265-params pools`.

**Divergência registrada com o A8 §26.** Aquela seção conclui que "`-threads` entra apenas no comando
de conversão". Nunca foi implementado — logo a pergunta que a Fase 4 teve de responder não era
"manter?", e sim "vale acrescentar agora?". A resposta é **não**, pelos dois caminhos:

- **Caminho CPU.** `-threads N` limitaria as threads de codificação do libx265. O que já limita CPU
  são dois controles por processo, ambos existentes: o semáforo justo de
  `ExternalToolGate.FFMPEG_TRANSCODE` e, depois desta fase, o `concurrencyLimit()` do
  `ConversionJobHandler` — que ficou no default de um e substituiu o `AtomicBoolean` do runner
  removido. Com **um transcode por vez**, saturar a máquina é o comportamento desejado: é trabalho que
  o usuário pediu explicitamente e que já leva horas. `-threads` só faria sentido para dividir a
  máquina com outra coisa, e nada no produto pede isso.
- **Caminho GPU.** Com `hevc_nvenc`/`hevc_qsv`/`hevc_amf` a codificação acontece no ASIC, onde
  `-threads` não chega. A CPU continua no demux, no decode, no filtro e no mux — e é o **decodificador
  por software** que usa threads. Limitá-las estrangularia a alimentação da GPU e derrubaria o
  throughput, que é exatamente o "não perder desempenho apenas para simplificar arquitetura" que o
  contrato desta migração proíbe.

**Decisão: não acrescentar `-threads` em nenhum dos dois caminhos.** O controle certo é por
processo/execução — `ExternalToolGate` limita quantos ffmpeg coexistem, `concurrencyLimit()` limita
quantas conversões coexistem —, e a seleção de encoder com queda para CPU fica intacta.

**O que o código garante e o que exige medição manual.** O código garante a escolha do encoder, o
fallback e os dois limites de concorrência: tudo isso é afirmado por teste. O que **nenhuma suíte pode
afirmar** é o ganho relativo GPU × CPU e o efeito que um eventual `-threads` teria sobre o throughput
— isso exige benchmark em máquina com placa real e vídeos reais. Fica registrado como **aceitação
manual pendente**, medindo o tempo de parede de um mesmo lote nos dois perfis; não bloqueia a fase,
porque nada aqui depende do número para estar correto.

## VII.7 O que a Fase 4 deliberadamente não fez

- Não migrou os leitores pesados (backlogs de pHash e de fingerprint, similaridades, rebuilds de
  metadata e localização, dataset geográfico): são a Fase 5.
- Não migrou o preview da organização, pelo motivo em VII.2.
- Não transformou operação interativa de um item em workload assíncrono: restore unitário de
  quarentena, rename e exclusão pelo Explorer continuam síncronos na App, com locks e auto-escrita.
- Não removeu nada que ainda tenha dono: a limpeza foi só a dos motores que passariam a competir.
- Não mexeu no piso de cobertura, nem para baixo nem para cima: a diferença ficou registrada como
  pendência em VII.8, e o README aponta para ela em vez de reescrever os números.

## VII.8 Medição

Build limpo, PostgreSQL real, árvore principal:

```text
Tests:       3013 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98.39% instruction, 92.33% branch, 97.85% line, 98.85% method, 100.00% class
SpotBugs:    -Pspotbugs verify verde, BugInstance size is 0, nenhuma exclusão nova
Sonar:       0 issues abertas
```

Os dez pulados são de plataforma (symlink/junction, só-Windows), não de ferramenta ausente: o teste
que depende do ffmpeg rodou.

**O piso não foi alcançado, e não foi alterado.** A fase fecha abaixo dele em quatro métricas, e a
distância é pequena o bastante para caber em unidades:

| Métrica | Medido | Piso | Falta |
| --- | --- | --- | --- |
| Instrução | 98,39 % | 98,49 % | 67 instruções de 67.460 |
| Branch | 92,33 % | 92,43 % | 6 branches de 5.853 |
| Linha | 97,85 % | 98,02 % | 23 linhas de 13.533 |
| Método | 98,85 % | 98,92 % | 2 métodos de 3.027 |

A colheita honesta veio primeiro, e foi grande: as classes da fase saíram do topo das lacunas — de
98,09 / 91,95 / 97,61 / 98,32 no início do gate para os números acima —, e nas doze classes que esta
branch tocou sobram 94 instruções descobertas, quase todas em caminhos de falha de I/O e de rollback.
Onde a lacuna era guarda redundante, o `AGENTS.md` manda reestruturar em vez de anotar, e foi o que
aconteceu: quatro guardas `execution != null` saíram do código — três no `OrganizationExecutor` e uma
no `ExplorerDeletionService` —, porque nenhum dos dois caminhos recebe execução nula.

O que sobra é resíduo do tipo que o próprio documento nomeia como aceito, e **sozinho ele já é maior
que a diferença**: 14 construtores privados anti-instanciação somam 98 instruções, 28 linhas e 14
métodos descobertos, e cobri-los exige a reflection que a regra proíbe. O restante são caminhos que
dependem do sistema operacional negar algo (`InterruptedException` no meio de uma espera de lock,
`SQLException` de um banco que reiniciou, um rollback que também falha) e o glue que inicia a segunda
JVM, já declarado como resíduo antes desta fase.

Fechar os 6 branches que faltam exigiria testar guardas defensivas — teste artificial, que a regra
base recusa. Baixar o piso exige o procedimento de *Recalcular o piso*, que é decisão do dono do
projeto e não foi tomada aqui. **Fica registrado como pendência explícita, para decisão.**

---

# Parte VIII — Fase 4.1: a fronteira arquitetural App × Worker

Fase de **análise e modelagem**. Nada de produção foi alterado aqui: o que segue é o levantamento do
código real, o confronto com a hipótese arquitetural e uma recomendação para decisão.

## VIII.1 Motivação

A divisão App × Worker chegou até aqui construída por acumulação. Cada fase perguntou "isto é pesado?
escreve? é interativo?" e respondeu caso a caso. Funcionou como método de migração e produziu uma
topologia que roda — mas não produziu uma **fronteira**: produziu uma lista de decisões, cada uma
defensável isoladamente, sem um critério que permita decidir o próximo caso sem reabrir a discussão.

O sintoma apareceu no fechamento da Fase 4. A reclassificação do `QUARANTINE_RESTORE` não foi uma
correção de erro: foi a descoberta de que a régua usada — o `ExecutionType` — não descrevia a coisa
certa. Se a régua precisou mudar uma vez por acidente, ela vai precisar mudar de novo, e a Fase 5
traz sete workloads para classificar.

Esta fase existe para responder, antes disso: **qual é o critério?**

## VIII.2 Estado atual: onde a fronteira realmente está

Levantado do código, não do desenho.

**A fronteira não é o `@Profile`.** Existem 44 anotações de perfil no código de produção, e elas
cobrem *quem inicia* trabalho (schedulers, watchers, runners, o dispatcher, o supervisor), não *quem
sabe executá-lo*. Praticamente todo serviço de domínio — `QuarantineService`, `OrganizationExecutor`,
`VideoConversionService`, `DuplicateDeletionService`, `ExplorerDeletionService` — **é instanciado nos
dois processos**. Nada no contêiner impede a App de executar qualquer um deles.

O que separa os dois processos, na prática, são três coisas de naturezas bem diferentes:

| Mecanismo | O que garante | Força |
| --- | --- | --- |
| `--spring.main.web-application-type=none` no Worker | nenhum endpoint é alcançável no Worker | forte: não há servidor |
| `@Profile` nos iniciadores | só a App agenda, observa e supervisiona | forte, verificável |
| **Quem chama** o serviço | tudo o mais | **nenhuma**: é convenção |

A terceira linha é a fronteira de verdade — e ela não é uma fronteira, é um hábito. Toda vez que
alguém na App chama um serviço de domínio, a operação executa na App, com o processo, o heap e a
transação da requisição. É exatamente assim que existem os segundos motores da seção VIII.6.

**O que já atravessa corretamente.** A comunicação App → Worker é durável e unidirecional: a App
grava uma linha em `execution` (mais `request_payload`) e o Worker reivindica. De volta, o Worker
grava progresso, contadores, `current_item_percent`, passos e `conversion_item_result`, e a App lê.
Não existe canal efêmero Worker → App em lugar nenhum do código: nenhum WebSocket, nenhum SSE,
nenhuma chamada HTTP do Worker para a App. A preferência arquitetural que se queria estudar **já é o
estado atual**, e a Fase 4 a reforçou ao mover progresso e relatório de memória para a linha.

## VIII.3 A hipótese, e o que ela ainda não resolve

> App = interaction + intent (control plane) · Worker = execution + effects (execution plane)

Confrontada com o código, a hipótese **descreve bem** o que a Fase 4 produziu para os seis escritores
em lote, e **descreve mal** três regiões: as operações interativas do Explorer e da quarentena, os
planejamentos (preview), e os efeitos que atingem a própria instalação (update, ferramentas, backup).

O problema não é a hipótese estar errada. É que ela usa **uma palavra para duas coisas**: "execution"
significa tanto *a responsabilidade por executar disciplinadamente* (sob execução, lock, posse,
progresso durável, retry) quanto *o processo em que o código roda*. A Fase 4 tratou as duas como
sinônimos, e é daí que vêm as exceções que parecem arbitrárias.

Separando os dois sentidos, a hipótese se decompõe em duas afirmações independentes:

- **H1 (disciplina).** Todo efeito operacional sobre os dados do usuário acontece sob uma `Execution`,
  com lock de caminho, posse verificada no ponto de commit, progresso e resultado duráveis, e
  política explícita de retry, cancelamento e compensação.
- **H2 (localização).** Esse efeito acontece no processo Worker.

H1 é sobre corretude e é onde estão os defeitos reais. H2 é sobre isolamento de recursos e é onde
estão os custos. **Elas não precisam ter a mesma resposta**, e tratá-las como se precisassem é o que
produziu tanto as exceções quanto os segundos motores.

O restante desta parte testa as duas separadamente.
## VIII.4 Matriz das capabilities

Levantada dos dois lados: dos controllers, schedulers, watchers, `@Async`, runners, launchers e
handlers para baixo; e de cada efeito operacional (`SecureFileMove`, `Files.delete`, `Files.move`,
processo externo, mutação em massa de catálogo) para cima, atrás de **todos** os caminhos capazes de
produzi-lo.

Colunas abreviadas: **Det.** = onde a intenção fica determinada · **Exec.** = executor real hoje ·
**Proc.** = processo · **E** = tem `Execution` · **L** = lock de caminho · **O** = ownership ·
**SW** = self-write ancorado · **P** = progresso durável · **R** = retry/reclaim · **C** =
cancelamento cross-process.

| # | Capability | Gatilho | Interação | Det. | Exec. | Proc. | E | L | O | SW | P | R | C |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Inventário | tela, watcher, onboarding | nenhuma | App | `InventoryJobHandler` | **W** | sim | sim | n/a¹ | n/a | sim | sim | sim |
| 2 | Reconcile | scheduler, reclaim | nenhuma | App | `ReconcileJobHandler` | **W** | sim | sim | n/a¹ | n/a | sim | sim | sim |
| 3 | Organização — execute | tela | forma + confirmação | App | `OrganizationJobHandler` | **W** | sim | sim | sim | sim | sim | não² | sim |
| 4 | Organização — preview | tela | forma | App | `OrganizationAsyncRunner` | **A** | sim | sim | n/a | n/a | sim | não | sim |
| 5 | Undo de organização | tela | confirmação | App | `OrganizationUndoJobHandler` | **W** | sim | sim | sim | sim | sim | não² | sim |
| 6 | Conversão | tela | forma + confirmação | App | `ConversionJobHandler` | **W** | sim | sim | sim | sim | sim | não² | sim |
| 7 | Exclusão de duplicados | tela | seleção + confirmação | App | `DuplicateDeletionJobHandler` | **W** | sim | sim | sim | sim | sim | não² | sim |
| 8 | Quarentena — restore em lote | tela | seleção | App | `QuarantineRestoreJobHandler` | **W** | sim | sim | sim | sim | sim | não² | sim |
| 9 | Quarentena — restore unitário | tela | **diálogo de conflito** | App | `QuarantineService` | **A** | sim | sim | **não** | sim³ | não⁴ | não | **não** |
| 10 | Quarentena — purga | tela, scheduler | confirmação | App | `QuarantinePurgeJobHandler` | **W** | sim | sim | sim | sim | sim | não² | sim |
| 11 | Quarentena — limpeza de ausentes | tela | nenhuma | App | `QuarantinePurgeService` | **A** | sim | sim | **não** | n/a | não⁴ | não | **não** |
| 12 | **Explorer — quarentenar** | tela | confirmação | App | `ExplorerDeletionService` | **A** | **sim⁵** | sim | **não** | sim³ | **não** | **não** | **não** |
| 13 | **Explorer — apagar de vez** | tela | confirmação | App | `ExplorerDeletionService` | **A** | **não** | sim | **não** | sim³ | **não** | **não** | **não** |
| 14 | **Explorer — renomear** | tela | nome novo | App | `ExplorerRenameService` | **A** | **não** | sim | **não** | sim³ | **não** | **não** | **não** |
| 15 | **Similaridade de fotos** | tela **e API** | limiar | App | dois motores⁶ | **A** | não | não | n/a | n/a | **memória** | não | não |
| 16 | **Similaridade de vídeos** | tela **e API** | limiar | App | dois motores⁶ | **A** | não | não | n/a | n/a | **memória** | não | não |
| 17 | Backlog de pHash | startup, tela | nenhuma | App | `PhashBacklogAsyncRunner` | **A** | não⁷ | não | n/a | n/a | parcial⁷ | não | parcial |
| 18 | Backlog de fingerprint de vídeo | startup, tela | nenhuma | App | `VideoFingerprintBacklogAsyncRunner` | **A** | não⁷ | não | n/a | n/a | parcial⁷ | não | parcial |
| 19 | Rebuild de metadata | tela | forma | App | `MetadataRebuildAsyncRunner` | **A** | **não** | não | n/a | n/a | **memória** | não | não |
| 20 | Rebuild de localização | tela | nenhuma | App | `LocationRebuildAsyncRunner` | **A** | **não** | não | n/a | n/a | **memória** | não | não |
| 21 | Dataset geográfico | tela, scheduler | nenhuma | App | `GeoDatasetAsyncRunner` | **A** | não | não | n/a | n/a | **memória** | não | não |
| 22 | **Purga de catálogo ausente** | scheduler | nenhuma | — | `CatalogFileRetentionService` | **A** | **não** | **não** | n/a | n/a | **não** | não | não |
| 23 | Troca de biblioteca | tela | confirmação | App | `LibrarySwitchService` | **A** | não | sim | n/a | n/a | não | não | n/a |
| 24 | Backup / restore do catálogo | tela | confirmação | App | `CatalogBackupAsyncRunner` | **A**⁸ | não | n/a | n/a | n/a | memória | não | parcial |
| 25 | Instalação de ferramenta externa | tela, bootstrap | nenhuma | App | `ExternalToolInstallAsyncRunner` | **A**⁸ | não | n/a | n/a | n/a | memória | não | não |
| 26 | Atualização da aplicação | tela, scheduler | confirmação | App | `UpdateInstallAsyncRunner` | **A**⁸ | não | n/a | n/a | n/a | memória | não | não |
| 27 | Miniatura sob demanda | requisição | nenhuma | — | `Photo/VideoThumbnailService` | **A** | não | não | n/a | n/a | n/a | n/a | n/a |

¹ leitores: não mutam a biblioteca, então não há commit a proteger.
² não resumível **por decisão**: o reclaim encerra como `INTERRUPTED` e compensa com `RECONCILE`.
³ anuncia self-write **sem `executionId`**, dependendo do teto fixo de cinco minutos.
⁴ escreve contadores na linha, mas o processo que os escreve é o mesmo que responde à requisição.
⁵ abre uma `Execution` `DEDUP_DELETE` **fora da fila**, sem claim, sem lease e sem posse.
⁶ runner assíncrono pela tela **e** cálculo síncrono dentro da requisição pela API REST.
⁷ há `fingerprint_job_run`, mas o estado vivo (`running`, `processed`, `currentRunId`) é memória.
⁸ efeito sobre a **instalação**, não sobre a biblioteca — ver VIII.9.

### O que a matriz mostra

Quatro grupos aparecem sozinhos, e eles não coincidem com "pesado × leve":

- **Linhas 1–8, 10** — disciplina completa. É o que a Fase 4 produziu.
- **Linhas 9, 11–14** — efeito sobre a biblioteca **sem** posse, sem cancelamento, sem retry, com
  self-write frouxo, e em três casos (12–14) sem sequer aparecer no histórico. É aqui que estão os
  defeitos, e nenhum deles é sobre onde o código roda: são sobre **disciplina ausente** (H1).
- **Linhas 15–21** — Fase 5. O padrão comum é estado vivo em memória de processo.
- **Linhas 22–26** — efeitos que não são sobre a biblioteca: catálogo em massa (22), a instalação
  (24–26), a configuração (23).
## VIII.5 Violações encontradas

Ordenadas por gravidade, com o cenário que as torna visíveis.

**V1 — Uma execução da App pode ser declarada interrompida enquanto roda.**
`InventoryLauncherService.launch` chama `executionProgressService.markInterruptedExecutions()` **a
cada inventário disparado**, não apenas no startup. Essa varredura encerra como `INTERRUPTED` toda
execução `RUNNING` sem lease vivo, protegida apenas por `ExecutionCancellationService.isLive`, que é
memória de processo — e **nem o Explorer nem o restore unitário se registram** ali. O
`InventoryWatchService` dispara inventário automaticamente ao ver mudança no filesystem, e a própria
quarentena pelo Explorer produz essas mudanças. Sequência inteiramente plausível: o usuário manda
quarentenar uma pasta pelo Explorer; o watcher reage; o inventário é enfileirado; a varredura marca a
execução do Explorer como `INTERRUPTED` com `finishedAt` preenchido; o Explorer termina e sobrescreve
para `FINISHED`. Duas escritas concorrentes na mesma linha, e um histórico que mente em qualquer uma
das ordens. **O `ExecutionReclaim` do Worker não tem esse defeito** — ele pergunta só por leases
expirados, justamente para não tocar no que a App executa —, o que mostra que o risco já era
conhecido de um lado da fronteira e não do outro.

**V2 — Um `Files.delete` recursivo sobre a biblioteca sem `Execution` nenhuma.**
O "apagar de vez" do Explorer (linha 13) remove arquivos e pastas do usuário definitivamente, e não
deixa registro algum: nenhuma linha, nenhum passo, nenhum contador. É o efeito mais irreversível do
produto — mais que a purga, que ao menos passou a rodar sob execução — e é o único que não pode ser
respondido depois com "o que aconteceu com meus arquivos?".

**V3 — Três efeitos sobre a biblioteca com self-write sem `executionId`.**
Explorer (quarentenar, apagar, renomear) e o restore unitário anunciam sem id, caindo no teto fixo de
cinco minutos. Para um rename é irrelevante. Para a quarentena de uma **pasta inteira** pelo Explorer
— que percorre `catalogedUnder(target)` sem teto — não é: passados cinco minutos, o watcher volta a
ler as próprias escritas do produto como alteração externa e dispara reconciliação sobre uma operação
em curso.

**V4 — Uma capability com dois motores, um deles dentro da requisição.**
`PhotoSimilarityService.groups()` e `VideoSimilarityService.groups()` **computam sob demanda se o
cache estiver frio** — O(n²) sobre até 8.000 candidatos, dentro da thread que atende
`GET /api/duplicates/similar-photos`. A tela usa o caminho assíncrono (`PhotoSimilarityAsyncRunner`);
a API usa o síncrono. Mesma capability, dois motores, e o resultado de ambos vive num
`ConcurrentHashMap` de processo.

**V5 — Purga de catálogo em massa sem execução, lock ou histórico.**
`CatalogFilePurgeScheduler` chama `deleteMissingBefore(cutoff)`, que apaga registros de catálogo em
bloco. É mutação de dados do usuário — o catálogo é o que o produto sabe sobre a coleção dele — e não
tem `Execution`, não aparece em tela nenhuma e não pode ser cancelada. Ficou na App por ser "curta e
periódica", que é exatamente o critério que esta fase recusa.

**V6 — O plano do preview mora num mapa de cinco entradas.**
`OrganizationPlanStore` guarda os últimos cinco planos em memória, chaveados por `executionId`. Um
restart da App entre o preview e o execute perde o plano; um sexto preview expulsa o primeiro. A tela
trata isso ("o plano não está mais aqui"), então não é defeito de corretude — mas é o exemplo mais
claro de estado operacional que atravessa uma fronteira de processo sem estar persistido.

**V7 — `filesFound` da purga da quarentena mente por construção na passagem diária.**
A passagem agendada enfileira com `filesFound = 0` porque o conjunto ainda não foi decidido, e a
tela de progresso divide por esse total. É consequência direta e correta de decidir o conjunto no
momento certo, mas o contrato de progresso não foi ajustado junto. Baixo impacto, registrado por
completude.

## VIII.6 Dois motores: o padrão, não os casos

Cinco capabilities têm hoje mais de um caminho de produção para o mesmo efeito:

| Capability | Motor A | Motor B | Diferença real entre eles |
| --- | --- | --- | --- |
| Mover para quarentena | `DuplicateDeletionJobHandler` (W) | `ExplorerDeletionService` (A) | posse, lease, retry, cancelamento, progresso |
| Restaurar da quarentena | `QuarantineRestoreJobHandler` (W) | `QuarantineService.restore` (A) | posse, cancelamento; **e a conversa** |
| Expurgar da quarentena | `QuarantinePurgeJobHandler` (W) | — | (unificado na Fase 4) |
| Agrupar semelhantes | `SimilarityGroupingRunner` (A, assíncrono) | `groups()` (A, na requisição) | onde bloqueia, e nada mais |
| Reconciliar catálogo | `ReconcileJobHandler` (W) | `CatalogFileRetentionService` (A) | escopo diferente, disciplina ausente em B |

O que os cinco têm em comum não é o processo: é que **o motor B nasceu depois, para atender um caso
de uso ligeiramente diferente, e herdou nada da disciplina do motor A**. Nenhum deles foi uma decisão
de "isto deve rodar na App"; todos foram "isto aqui é pequeno, faço direto".

É o padrão que importa. Uma correção feita no handler do Worker — uma guarda nova, um lock a mais, um
tratamento de falha — **não alcança o motor B**, e ninguém é avisado. Foi assim que a quarentena pelo
Explorer ficou sem posse e sem cancelamento por três fases inteiras sem que nada apontasse.

**Critério proposto para tratar dois motores** (não é sobre processo): uma capability pode ter mais de
um *ponto de entrada* — tela, API, scheduler, watcher —, mas deve ter **um só motor**, isto é, um
único código que produz o efeito, sob a mesma disciplina, seja qual for o hospedeiro. Quando a UX
exige resposta imediata, o que muda é *onde o motor é chamado*, nunca *qual motor*.

## VIII.7 Restore interativo, Explorer, cleanups e preview

### Restore unitário: a conversa termina antes da mutação

Hoje o `QuarantineService.restore` faz tudo num só passo: lê o movimento, detecta conflito de nome ou
pasta de origem ausente, **e**, se não houver conflito, move. Quando há conflito, devolve
`CONFLICT`/`ORIGIN_MISSING` e a tela pergunta ao usuário, que reenvia com `ConflictResolution.RENAME`
ou uma pasta alternativa. Ou seja: a conversa **já termina** antes da mutação. O que o código não faz
é separar as duas metades.

Separação possível, sem perder UX:

| Etapa | Onde pertence | O que produz |
| --- | --- | --- |
| Ler o movimento, checar origem e colisão | App | um diagnóstico, não uma mutação |
| Perguntar e receber a decisão | App | `QuarantineRestoreOptions` completa |
| **Intenção determinada** | App | comando: movimento + destino + resolução |
| Revalidar pré-condições, travar, mover, gravar catálogo | motor único | efeito |

**TOCTOU.** Entre a decisão do usuário e a mutação, o mundo pode mudar: o destino pode passar a
existir, o arquivo pode sair da quarentena, a pasta de origem pode ser criada ou apagada. Isso **já
acontece hoje** — a resposta ao diálogo é uma segunda requisição HTTP — e o código já lida: o
`restoreOne` revalida tudo sob o lock antes de mover, e `SecureFileMove` nunca sobrescreve. O que
falta não é revalidação, é **o Worker saber o que fazer quando a revalidação falha sem poder
perguntar**. A resposta é a que o lote já usa: não perguntar. Um item cuja pré-condição mudou volta
como `CONFLICT`/`ORIGIN_MISSING` no resultado, e a tela — que continua sendo quem conversa — reabre a
pergunta com o estado novo. Nenhuma decisão interativa acontece depois do claim; o que acontece é a
recusa determinística de um comando que deixou de ser válido.

**A conclusão da Fase 4 (manter na App) permanece defensável, mas por outro motivo.** Não porque "é
uma conversa" — a conversa termina antes —, e sim porque a operação é atômica, unitária e o usuário
está esperando a resposta na tela. Isso é um argumento de **localização** (H2), não de disciplina
(H1). A disciplina que falta (posse, cancelamento, self-write com id) deve ser corrigida
independentemente de onde a mutação roda.

### Explorer: onde termina a interação

Mesma análise, resultado diferente por escala.

- **Renomear** — um arquivo, milissegundos, resposta imediata na tela. A intenção fica determinada no
  instante em que o usuário confirma o nome. Nenhuma razão de disciplina para ir à fila; toda razão
  para ganhar `Execution` e self-write ancorado.
- **Apagar de vez / quarentenar** — pode alcançar **uma pasta inteira**, sem teto. A intenção fica
  determinada na confirmação, e a partir dali não há mais nada a perguntar: é exatamente o formato de
  um comando enfileirável. Aqui a diferença entre motor A e motor B é grande demais para ser deixada:
  é a mesma coisa que o `DEDUP_DELETE` faz, com menos garantias, e sem sequer aparecer no histórico
  quando é o modo "apagar de vez".

"O Explorer pertence à App" é verdade sobre a **navegação**. Não é um argumento sobre a mutação.

### Cleanups e schedulers: reclassificados pela responsabilidade

| Operação | Hoje | Pela responsabilidade | Observação |
| --- | --- | --- | --- |
| `ReconcileScheduler` | App: **enfileira** | correto | o padrão que os outros deveriam seguir |
| `QuarantinePurgeScheduler` | App: **enfileira** | correto | corrigido na Fase 4 |
| `CatalogFilePurgeScheduler` | App: **executa** | deveria enfileirar | muta catálogo em massa (V5) |
| `GeoDatasetAutoUpdateScheduler` | App: **executa** via runner | deveria enfileirar | Fase 5 |
| `UpdateCheckScheduler` | App: verifica e avisa | correto | não produz efeito operacional |
| Limpeza de ausentes da quarentena | App: **executa** | fronteira | ver abaixo |

A limpeza de ausentes é o caso em que a régua de "curta e simples" foi usada na Fase 4 e não
sobrevive a este exame: ela **apaga registros de movimento e de catálogo**, em lote, com teto de
5.000 por passagem. Não toca no disco, mas toca no que o produto sabe sobre a coleção. Pela
responsabilidade, pertence ao mesmo lugar que a purga.

### Preview: o caso em que responsabilidade e localização divergem

O preview é planejamento: lê a árvore, aplica regras, produz um plano de até centenas de milhares de
itens, **não muta nada**. Pela hipótese, planejamento é intenção e pertence à App — e a Fase 4 o
manteve lá.

Mas o preview é também o consumidor de memória mais agressivo do produto: o plano inteiro vive no
heap, e é o heap que atende as telas. O argumento para movê-lo ao Worker **não é de responsabilidade,
é de isolamento** — exatamente a distinção H1/H2. Se ele for para o Worker, o plano precisa
atravessar a fronteira (tabela ou serialização em workspace), e aí o custo não é do transporte: é que
um plano persistido vira estado operacional com ciclo de vida, retenção e invalidação próprios.

**As duas decisões devem ser tomadas separadamente**, e nesta ordem: primeiro se o preview roda sob
disciplina de execução (hoje já roda: tem `Execution`, lock e progresso); depois, e só por medição de
heap, se vale movê-lo de processo.
## VIII.8 Impacto sobre cada workload da Fase 5

O A8 §13/§19 manda sete para o Worker. Confrontando cada um com a fronteira — e separando "vai porque
é execução" de "vai porque é pesado":

| # | Workload | Por que iria | Responsabilidade ou peso? | O que precisa atravessar | Estado em memória que some |
| --- | --- | --- | --- | --- | --- |
| 17 | Backlog de pHash | produz efeito (grava fingerprints) e dura horas | **ambos** | nada: o backlog é uma consulta | `running`, `processed`, `failed`, `currentRunId`, `lastError`, `stopRequested` |
| 18 | Backlog de fingerprint de vídeo | idem, com ffmpeg | **ambos** | nada | idem |
| 15 | Similaridade de fotos | O(n²) em heap, resultado em cache de processo | **peso** — não muta nada | limiar; **e o resultado** | `SimilarityGroupCache`, `running`, `processed`, `total` |
| 16 | Similaridade de vídeos | idem | **peso** | idem | idem |
| 19 | Rebuild de metadata | grava metadata do catálogo, chama exiftool/mediainfo | **ambos** | escopo (pasta, campos, filtros) | `processed`, `total`, `startedAtMillis` |
| 20 | Rebuild de localização | grava localização no catálogo | **ambos** | escopo | idem |
| 21 | Dataset geográfico | baixa da rede e importa; escreve no workspace | **ambos** | nada (a fonte é configuração) | `GeoDatasetProgress` inteiro |

### O que muda no desenho previsto

**Nenhum dos sete tem `Execution` hoje** (17 e 18 têm `fingerprint_job_run`, que registra a passagem
mas não é a linha da fila). Logo, para todos, a Fase 5 começa igual à Fase 4 começou: payload
versionado, launcher, handler, e o progresso saindo de `Atomic*` para a linha. Isso o A8 já prevê e
continua correto.

**Duas correções ao desenho previsto:**

1. **Similaridade não é "mais um workload".** É o único cujo **resultado** — não o progresso — vive em
   memória, e cujo consumo é um `Map` que a tela pagina. Movê-la ao Worker sem decidir onde o
   resultado passa a morar não é migração, é quebra: hoje `groups()` computa e devolve na mesma
   chamada. Antes de implementar, é preciso decidir se os grupos viram tabela (com invalidação por
   assinatura de fingerprint, que já existe) ou se ficam em cache de processo — e, neste segundo caso,
   ela **não pode** ir para o Worker, porque o consumidor está na App. Recomendo tratar a persistência
   dos grupos como pré-requisito, não como detalhe da migração.

2. **O segundo motor da similaridade tem de morrer junto.** `groups()` computando sob demanda dentro
   da requisição REST (V4) sobrevive a qualquer migração se ninguém o remover explicitamente — e aí a
   Fase 5 termina com o mesmo padrão que a Fase 4 gastou cinco slices eliminando.

**Um workload a mais que o A8 não lista:** a purga de catálogo ausente (linha 22 da matriz). Ela é
irmã do reconcile, muta o catálogo em massa e hoje roda sem `Execution`. Se a Fase 5 é a fase dos
leitores pesados e dos rebuilds, ela cabe ali com custo baixo.

## VIII.9 Confronto com as invariantes candidatas

Cada uma testada contra o código, com contraexemplo quando existe.

| Invariante | Veredito | Contraexemplo / qualificador necessário |
| --- | --- | --- |
| App owns interaction and intent | **confirmada** | nenhum: nada no Worker conversa com o usuário |
| Worker owns execution and effects | **rejeitada como está** | backup/restore do catálogo, instalação de ferramenta e atualização da aplicação são efeitos operacionais que **não podem** ir ao Worker: o restore derruba as conexões do próprio Worker, a instalação do ffmpeg é dependência circular dele, e a atualização substitui o jar do supervisor. Qualificador: **efeitos sobre a biblioteca e o catálogo do usuário** |
| App may prepare a mutation but should not materialize it | **rejeitada** | o restore unitário e o rename do Explorer materializam por decisão de UX, e a alternativa custa mais do que resolve. A forma defensável é *"a App não materializa por um caminho próprio"* — ver VIII.10 |
| Worker must not require interactive decisions after claiming | **confirmada** | nenhum handler pergunta nada; e a análise do restore mostra que a conversa termina antes |
| A capability should have one execution engine | **confirmada, e é a mais violada** | cinco casos em VIII.6 |
| Operational state crossing process/restart must be persisted | **confirmada** | violada por `OrganizationPlanStore`, `SimilarityGroupCache` e os `Atomic*` dos sete workloads da Fase 5 |
| Worker progress/results are persisted; App reads them | **confirmada** | já é verdade para tudo que passou pela fila; falso para o que não passou |
| TOCTOU handled by revalidation at the execution boundary | **confirmada** | já implementada em `restoreOne`, `evaluateGuards`, `purgeOne` e `undoOne` |
| Execution placement defined by responsibility, not weight | **parcialmente rejeitada** | o preview é o contraexemplo: a responsabilidade diz App, o heap diz Worker. Placement precisa de **dois critérios**, não de um |

### Tentativas de quebrar a hipótese

Buscando ativamente onde "App = intent, Worker = execution" produziria dano:

- **Explosão de execuções triviais.** Renomear um arquivo viraria linha na fila, claim, lock, lease e
  duas escritas de status para uma operação de 3 ms. O histórico — que hoje conta a história das
  operações reais — passaria a ser dominado por renames. **Dano real.**
- **Perda de UX.** O Explorer responde hoje "renomeado" na mesma requisição. Pela fila, responderia
  "enfileirado", e a tela precisaria de um poller para uma operação que termina antes do poller
  começar. **Dano real**, e desproporcional.
- **Latência do claim.** O `poll-seconds` do worker é 5. Uma operação interativa esperaria até cinco
  segundos para *começar*. **Dano real** (mitigável com notificação, mas isso é infraestrutura nova).
- **Inconsistência transacional.** O Explorer hoje move e grava catálogo na mesma thread; a fila não
  muda isso (o handler faria o mesmo), então **não é dano**.
- **Dependência circular.** Instalar o ffmpeg no Worker exigiria o Worker para instalar o ffmpeg do
  Worker. **Dano real**, já reconhecido pelo A8 §19.
- **Recuperação.** Uma fila cheia de operações interativas abandonadas por restart produz um conjunto
  de `INTERRUPTED` que ninguém sabe reconciliar, porque a intenção era de um usuário que já saiu.
  **Dano real.**
- **TOCTOU.** Analisado em VIII.7: **não é dano**, o mecanismo já existe.

Conclusão do exercício: **H1 não produz dano em nenhum caso testado. H2 produz dano em quatro.** A
hipótese é sólida onde fala de disciplina e frágil onde fala de localização — que é exatamente o
recorte que VIII.3 antecipou.

## VIII.10 Alternativas arquiteturais

**A. Manter a classificação atual** (peso, writer, interatividade, caso a caso).
Custo zero, e é o estado de hoje. Rejeitada: não é uma fronteira, não decide o próximo caso, e é a
causa direta das sete violações. Não é "arquitetura adequada porém trabalhosa" — é arquitetura
ausente.

**B. Fronteira estrita: todo efeito sobre a biblioteca vai ao Worker, sem exceção.**
Coerente e verificável por gate automático. Paga os quatro danos de H2: rename e restore unitário
viram operações de fila com latência de claim, o histórico enche de linhas triviais, e a recuperação
de operações interativas abandonadas fica sem dono. Não é inadequada — é **adequada e cara na UX**,
com o custo caindo justamente nas operações que o usuário faz olhando para a tela.

**C. Fronteira estrita com execução prioritária/inline para operações interativas.**
Como B, mais um caminho rápido: a App enfileira e o Worker atende primeiro. Reduz a latência mas não a
elimina, e acrescenta prioridade — mecanismo novo — a uma fila que hoje é simples. Mantém todos os
outros custos de B.

**D. Motor único, hospedeiro decidido por regra.**
Separa H1 de H2 e responde as duas explicitamente:

- **H1, sem exceção:** todo efeito sobre a biblioteca ou o catálogo do usuário acontece por **um único
  motor** — um `ExecutionJobHandler` por `ExecutionType` —, sob `Execution`, lock de caminho, posse
  verificada no commit, self-write ancorado no `executionId`, progresso e resultado duráveis.
- **H2, com regra:** o hospedeiro padrão é o **Worker**. A App pode hospedar a execução **do mesmo
  motor** quando as três condições valem juntas: (a) o comando é unitário e atômico, (b) o usuário
  está esperando a resposta, (c) a operação não é cancelável de forma útil. Nesse caso a App reivindica
  a própria execução pelo mesmo mecanismo — claim, lease, posse —, apenas sem passar pela fila.

O ponto de D é que **elimina o defeito real** (segundos motores, disciplina ausente) sem pagar os
danos de H2. Uma correção no handler alcança os dois hospedeiros porque **é o mesmo handler**. E a
regra é estreita o bastante para ser verificável: hoje ela admitiria exatamente três operações
(rename, restore unitário, e o Explorer sobre um único arquivo).

**Custo honesto de D:** exige que a App saiba reivindicar uma execução in-process (claim + lease sem
fila), o que hoje só o `ExecutionDispatcher` sabe fazer e vive sob `@Profile(WORKER)`. Extrair esse
núcleo para um componente compartilhado é a maior peça de trabalho da convergência.

## VIII.11 Trade-offs e riscos

| | B (estrita) | C (estrita + prioridade) | **D (motor único)** |
| --- | --- | --- | --- |
| Elimina segundos motores | sim | sim | **sim** |
| Disciplina uniforme | sim | sim | **sim** |
| UX interativa preservada | **não** | parcial | **sim** |
| Mecanismo novo necessário | fila para triviais | + prioridade | claim in-process |
| Verificável por gate | sim, simples | sim | sim, mais nuance |
| Risco de virar exceção-por-conveniência | baixo | baixo | **médio** — a regra dos três critérios precisa ser dura |
| Isolamento de heap para o interativo | sim | sim | não (é o ponto) |

**Riscos específicos de D**, com mitigação:

- *A regra vira desculpa.* Mitigação: os três critérios são condições **conjuntas**, e a lista de
  operações que as satisfazem cabe num teste que falha quando cresce.
- *Claim in-process diverge do claim do worker.* Mitigação: é o mesmo código, extraído — se divergir, é
  porque foi duplicado, que é o defeito que D existe para matar.
- *Uma operação "unitária" cresce.* O Explorer é o exemplo: renomear é unitário, quarentenar uma pasta
  não é. A regra tem de ser aplicada à **operação**, não à tela.

## VIII.12 Proposta recomendada

**Alternativa D**, com esta formulação das invariantes:

1. **A App é dona da interação e da intenção.** Nenhum diálogo com o usuário acontece depois de a
   execução começar.
2. **Todo efeito sobre a biblioteca ou o catálogo do usuário roda sob disciplina de execução** —
   `Execution`, lock, posse no commit, self-write ancorado, progresso e resultado duráveis, política
   explícita de retry, cancelamento e compensação. Sem exceção.
3. **Uma capability tem um só motor.** Pontos de entrada podem ser vários; o código que produz o
   efeito é um.
4. **O hospedeiro padrão é o Worker.** A App só hospeda quando o comando é unitário e atômico, o
   usuário está esperando, e o cancelamento não teria utilidade — e mesmo então, pelo mesmo motor e
   sob a mesma disciplina.
5. **Efeitos sobre a instalação** (atualização, ferramentas externas, backup/restore do cluster) são
   da App por natureza, não por exceção: o Worker é subordinado a ela nos três casos.
6. **Estado operacional que atravessa processo ou restart é persistido.** Cache em memória é
   otimização, nunca a verdade.
7. **Placement é decidido por responsabilidade; isolamento de recursos é uma segunda decisão**, tomada
   por medição e registrada separadamente.

## VIII.13 O que seria preciso para chegar lá

| # | Mudança | Alcance | Complexidade |
| --- | --- | --- | --- |
| 1 | Corrigir V1: `markInterruptedExecutions` não pode encerrar execução viva de outro caminho | pequeno, cirúrgico | baixa |
| 2 | Dar `Execution` + self-write ancorado ao rename e ao "apagar de vez" do Explorer | 2 serviços | baixa |
| 3 | Unificar a quarentena pelo Explorer no motor do `DEDUP_DELETE` | 1 serviço, 1 handler | **média** |
| 4 | Extrair o núcleo de claim/lease/posse para uso in-process pela App | worker → shared | **alta** |
| 5 | Restore unitário e limpeza de ausentes passam a rodar pelo motor único | 2 serviços | média |
| 6 | Purga de catálogo ausente ganha `Execution` e vai à fila | 1 scheduler | baixa |
| 7 | Similaridade: decidir a casa do resultado antes de migrar | decisão + tabela | **alta** |
| 8 | Fase 5: os sete workloads pelo padrão da Fase 4 | 7 capabilities | alta (é a Fase 5) |
| 9 | Preview: medir heap e decidir placement separadamente | medição | média |

## VIII.14 Sequência sugerida

**Antes da Fase 5** — o que protege o que já existe e é barato:

- V1 (corrida do `markInterruptedExecutions`) — é defeito ativo, não dívida de arquitetura.
- Item 2: `Execution` e self-write ancorado para as três mutações do Explorer.
- Decisão do item 7 (casa do resultado da similaridade), porque **muda o desenho** da Fase 5.

**Incorporada à Fase 5** — o que fica natural fazer junto:

- Itens 3, 5 e 6: as capabilities que já vão ser tocadas ganham o motor único.
- Item 8: os sete workloads, com o padrão da Fase 4 e as duas correções de VIII.8.

**Depois da Fase 5** — o que exige a fundação pronta:

- Item 4 (claim in-process), que só paga depois que houver mais de um caso a servir.
- Item 9 (placement do preview), que depende de medir com a carga da Fase 5 já no Worker.

## VIII.15 Gates que poderiam proteger estas invariantes

Somente análise; nada implementado nesta fase.

| Invariante | Verificação possível | Ferramenta |
| --- | --- | --- |
| Motor único por capability | todo `ExecutionType` tem exatamente um `ExecutionJobHandler`, e todo handler tem um tipo | teste próprio sobre o contexto |
| Efeito sob disciplina | nenhuma classe fora de um handler (ou do caminho autorizado) chama `SecureFileMove.move`, `Files.delete` ou `Files.move` sobre caminho da biblioteca | ArchUnit ou varredura de origem |
| Sem segundo motor | nenhum controller chama diretamente um serviço que muta a biblioteca; só launchers | ArchUnit |
| Self-write ancorado | toda chamada a `announce` a partir de um handler passa `executionId` | varredura de origem |
| App não conversa depois do claim | nenhum handler depende de `MessageSource` com locale de requisição, de `Authentication` ou de `HttpServletRequest` | ArchUnit |
| Composição por papel | subir o contexto `app` e o `worker` e afirmar quais beans existem em cada um | teste de composição (já existe embrião) |
| Estado durável | nenhum campo `static`/`Atomic*` de serviço é lido por controller como fonte de verdade | varredura, imprecisa |

Os três primeiros são os que pagam. O último é o mais difícil de expressar sem falso positivo.

## VIII.16 Decisões que dependem de você

1. **Adotar D, B ou C?** A recomendação é D; B é defensável se você preferir uma regra sem exceções ao
   custo da UX interativa.
2. **A similaridade guarda o resultado em tabela?** Sem essa decisão, a Fase 5 não pode migrá-la com
   segurança.
3. **O Explorer "apagar de vez" ganha `Execution` — e passa a ser cancelável?** Ganhar linha é barato;
   ser cancelável muda a UX.
4. **A purga de catálogo ausente entra na Fase 5** ou fica como está com a violação registrada?
5. **O preview deve ser medido agora** (heap sob plano grande) ou a decisão fica para depois da Fase 5?
6. **Os itens de VIII.14 marcados "antes da Fase 5" podem ser implementados**, ou a Fase 5 começa antes
   e eles entram junto?

---

# Fase 4.1A — App × Worker Boundary

Este slice transforma a evidência da discovery (VIII.1–VIII.16) em **decisão arquitetural
normativa**. A discovery permanece acima como está, inclusive onde é refutada aqui; VIII.31 registra
o que mudou e por quê.

**Ordem de decisão adotada**, explicitamente invertida em relação à discovery: primeiro qual
arquitetura é correta para o Nimbus, depois quais invariantes ela exige, depois qual alternativa
produz menos exceções e menor risco de divergência, depois qual é testável e recuperável — e **só
então** quanto custa convergir. Onde a discovery deixou o custo influenciar a recomendação, este
slice reabre a questão.

## VIII.17 Taxonomia de efeitos, e H1 refinada

"Efeito" não é sinônimo de filesystem. Nove categorias aparecem no código, e elas não pedem a mesma
disciplina:

| # | Categoria | Exemplo real | Reversível? | Precisa de H1? |
| --- | --- | --- | --- | --- |
| E1 | **Mutação da biblioteca** | mover, renomear, apagar mídia do usuário | não (só por undo/quarentena) | **sim** |
| E2 | **Mutação do catálogo** sobre o que o produto sabe da coleção | `deleteMissingBefore`, `applyRestore`, rebuild de metadata | parcialmente | **sim** |
| E3 | **Escrita em workspace/cache** derivada e regenerável | miniatura, temporário de conversão, arquivo decodificável do pHash | sim, por regeneração | **não** |
| E4 | **Alteração de configuração** | `AppSetting`, preferência de usuário, pasta observada | sim | não |
| E5 | **Controle de processo** | subir/derrubar o Worker, encerrar a App para atualizar | n/a | não |
| E6 | **Leitura pesada** | preview, similaridade, varredura do inventário | n/a (não muta) | **parcial** — ver abaixo |
| E7 | **Cálculo puro** | SSIM, distância de pHash, formatação | n/a | não |
| E8 | **Manutenção da instalação** | baixar ffmpeg, instalar atualização, `pg_dump`/`pg_restore` | não | não — ver E8′ |
| E9 | **Operação administrativa que muda a premissa das outras** | trocar de biblioteca | não | **sim**, parcialmente |

**H1′ (refinada).** A disciplina de execução é exigida por **E1, E2 e E9**, e por **E6 quando o
resultado da leitura é consumido por outro processo ou precisa sobreviver a um restart**. Não é
exigida por E3, E4, E5, E7 e E8.

O refinamento importa por três motivos concretos:

- **E6 entra parcialmente.** A discovery classificou leitura como "sem efeito, sem disciplina". Mas o
  preview e a similaridade **produzem estado operacional** (um plano, um agrupamento) que a UI
  consome depois, possivelmente noutro processo. Um leitor cujo resultado é efêmero (contar arquivos
  para uma tela) não precisa de nada; um leitor cujo resultado é um artefato consumido depois precisa
  de linha, progresso e um lugar durável para o resultado.
- **E8 sai, e sai por uma razão de dependência, não de conveniência.** A atualização substitui o jar
  do supervisor; a instalação de ferramenta é pré-requisito do Worker; o restore do cluster derruba
  as conexões do próprio Worker. Não é "a App pode fazer porque é mais fácil": é que **o Worker é
  subordinado à App nessas três**, e um subordinado não pode ser quem substitui, provê ou reinicia
  seu supervisor. É dependência circular, não custo.
- **E9 é uma categoria nova** que a discovery não tinha. Trocar de biblioteca cancela execuções,
  valida a pasta nova, limpa o catálogo e apaga o cache: muda a premissa de todo o resto. Precisa de
  disciplina (registro, exclusão, recuperabilidade) mas **não** é trabalho de domínio enfileirável no
  sentido comum — é uma transição de estado da instalação.

## VIII.18 Definições normativas

### App — responsabilidade

A App é a **fronteira com o usuário e a dona da intenção**. Sua responsabilidade é levar uma
interação até um comando suficientemente determinado, e apresentar o que aconteceu depois.

**Pertence à App:** o contexto HTTP, a sessão, a autenticação, o locale, as preferências de tela; a
validação de entrada; o diálogo — inclusive diálogos de várias rodadas, como conflito de nome; a
resolução de ambiguidade; a decisão de *quando* pedir trabalho (schedulers e watchers são produtores
de intenção); a criação e o cancelamento de `Execution`; a leitura e apresentação de progresso e
resultado; e a supervisão do ciclo de vida do processo Worker e da instalação (E5, E8).

**A App pode:** consultar qualquer estado; calcular (E7); escrever configuração (E4); produzir
artefatos derivados sob demanda para a própria resposta (E3, como uma miniatura); e **aguardar** a
conclusão de uma execução para responder de forma síncrona ao usuário.

**A App não pode:** produzir efeito de categoria E1 ou E2. Não pode abrir uma `Execution` e executá-la
ela mesma. Não pode chamar um serviço de domínio mutador. Não pode ser a única testemunha de um
resultado que a UI precisa (nada de verdade só em memória).

**Onde termina a interação:** no instante em que existe um comando completo — todos os parâmetros
resolvidos, todas as perguntas respondidas, nada mais dependendo do usuário. Esse instante é
observável: é quando o comando pode ser serializado sem perder nada.

### Worker — responsabilidade

O Worker **trabalha**: recebe um comando já determinado e o realiza sob disciplina, reportando de
forma durável.

**Trabalhar significa:** reivindicar a `Execution`, respeitar o limite por categoria, tomar os locks
de caminho, estabelecer e manter a posse, executar o efeito, verificar a posse imediatamente antes de
cada mutação irreversível, anunciar as auto-escritas ancoradas na execução, persistir progresso e
resultado, e encerrar em um estado terminal explícito — `FINISHED`, `FINISHED_WITH_ERRORS`, `ERROR`,
`CANCELLED`, `INTERRUPTED` ou `REJECTED`.

**Pertence ao Worker:** todo efeito E1 e E2; E6 quando o resultado é um artefato consumido depois; e
a política de retry, reclaim, compensação e cancelamento cooperativo.

**Não pertence ao Worker:** conversar com o usuário; resolver ambiguidade; decidir *se* o trabalho
deve ser pedido; qualquer dependência de HTTP, sessão, autenticação ou locale de requisição; efeitos
E5 e E8; e a supervisão de si mesmo.

**Regra de ouro do Worker:** depois do claim, nenhuma pergunta. Uma pré-condição que deixou de valer
não vira pergunta — vira resultado, e a App decide o que fazer com ele.

### Os três conceitos, separados

| Conceito | Definição | Onde vive | Durável? |
| --- | --- | --- | --- |
| **INTERACTION** | a conversa: requisição, validação, diálogo, decisão do usuário | App, na thread da requisição | não — e não precisa ser |
| **COMMAND / INTENT** | descrição completa e suficiente do trabalho pedido | linha `execution` + `request_payload` | **sim, sempre** |
| **EXECUTION** | a realização do efeito | Worker | **sim**, progresso e resultado |

O teste que separa INTERACTION de COMMAND é: *o comando ainda precisa do usuário?* Se sim, ainda é
interação. Se não, a interação acabou — independentemente de a operação levar 3 ms ou 6 horas.

**Aplicado aos casos difíceis:**

- **Restore com conflito.** A detecção do conflito é leitura; a pergunta é interação; a resposta
  (`RENAME`, pasta alternativa, `SKIP`) completa o comando. A partir daí não há mais nada a
  perguntar. A separação **resolve** o caso: hoje o código já faz as duas metades, só não as nomeia.
- **Explorer.** Renomear: o comando fica completo quando o usuário confirma o nome. Quarentenar ou
  apagar uma pasta: completo na confirmação. Em nenhum dos três há pergunta posterior.
- **Preview.** É um leitor cujo resultado é um artefato. O comando é o formulário; o resultado é o
  plano. A separação mostra que o problema do preview não é onde ele roda — é que o **resultado dele
  não é durável**.
- **Operações unitárias.** Um rename tem exatamente a mesma estrutura de um lote: interação, comando,
  execução. O que muda é a cardinalidade, que não é um conceito arquitetural.

**Uma operação interativa pode, sim, ter a forma "App pergunta → usuário decide → App produz comando
completo → motor executa" sem que a mutação fique na thread HTTP.** Nada no fluxo exige a mutação
dentro da requisição; o que exige é a *resposta* — e resposta é apresentação, não execução.
## VIII.19 Autópsia da alternativa D — "o mesmo handler, hospedado pela App"

A discovery recomendou D. Este slice a examina no nível em que ela precisa ser examinada: o que
exatamente é "o motor", e o que sobra dele quando a App o hospeda.

### O que o dispatcher faz, passo a passo

`ExecutionDispatcher.dispatchOne()` executa doze coisas, e **o handler é a décima**:

1. verifica se há operação administrativa segurando o trabalho de fundo;
2. pergunta quais tipos têm vaga (`CategoryConcurrency.typesWithCapacity`);
3. reivindica uma linha com lease (`ExecutionQueue.reserve`);
4. entra na categoria (`tryEnter`) ou devolve;
5. toma os locks das duas pontas (`OperationLockService.acquireFor`) e obtém a **posse**;
6. registra a posse no renovador de lease;
7. conta a tentativa (`countAttempt`) — e recusa se o orçamento acabou;
8. confirma a posse antes de começar;
9. **chama o handler**;
10. classifica a exceção: posse perdida → `INTERRUPTED`; lock ocupado → devolve à fila; falha
    passageira → devolve; falha permanente → `ERROR`;
11. devolve a execução à fila ou a rejeita se já houver sucessora;
12. libera lease e categoria.

**"Motor" não é o handler.** O handler é a parte específica do domínio; o motor é este ciclo. Chamar
o handler de outro lugar entrega o *que fazer* sem nada do *como executar disciplinadamente*.

### O que desaparece se a App chamar o handler diretamente

| Garantia | Quem a produz hoje | Sobrevive a "App chama o handler"? |
| --- | --- | --- |
| Claim atômico (uma linha, um executor) | `ExecutionQueue.reserve` | **não** |
| Lease e renovação | `LeaseRenewer` | **não** |
| Limite por categoria | `CategoryConcurrency` | **não** |
| Contagem de tentativa / poison job | `countAttempt` | **não** |
| Posse (`ExecutionOwnership`) | `acquireFor` | só se a App repetir o passo |
| Classificação de falha e requeue | `afterFailure` / `RetryPolicy` | **não** |
| Recuperação de abandono | `ExecutionReclaim` (só leases expirados) | **não** — sem lease, o reclaim ignora |
| Estado terminal correto | o dispatcher, quando o handler não escreveu | **não** |
| Pausa por operação administrativa | `backgroundWorkPaused` | **não** |

Nove das dez garantias somem. É exatamente a assinatura das violações V1–V3 da discovery: a
`Execution` do Explorer existe, mas é uma linha sem claim, sem lease, sem posse e invisível para o
reclaim — e por isso mesmo vulnerável a ser declarada interrompida por outro caminho (V1).

### Para preservar tudo, seria preciso extrair um `ExecutionEngine`

Sim — e é aqui que D se desfaz. Para a App hospedar sem perder garantias, ela precisaria de:

- um claim **por id** (reivindicar a execução que ela mesma acabou de criar), que hoje não existe: o
  `reserve` seleciona da fila por prioridade;
- lease e renovação dentro do processo App;
- o limite por categoria compartilhado entre dois processos — que hoje é um semáforo em memória do
  Worker e, portanto, **já não seria o mesmo limite**;
- a mesma classificação de falha, o mesmo requeue, a mesma pausa administrativa;
- e uma regra que impeça o Worker de reivindicar a mesma linha na janela entre criar e reivindicar.

Isso não é "um motor com dois hospedeiros". É **um motor e um segundo agendador**, com uma política
de concorrência que precisa ser reimplementada de forma distribuída para continuar significando a
mesma coisa. O `CategoryConcurrency` é a prova mais dura: em D, "no máximo uma conversão por vez"
deixa de ser verificável num semáforo e passa a exigir coordenação entre processos — ou deixa de
valer.

E o risco que D existe para evitar volta pela porta dos fundos: com dois caminhos de entrada no
motor, **uma correção feita no dispatcher não alcança o caminho da App** — que foi precisamente como
o Explorer ficou sem posse por três fases.

### Conclusão sobre D

> **D é elegante conceitualmente e, na prática, recria dois motores.** Não dois handlers — dois
> ciclos de execução. O handler compartilhado esconde a duplicação onde ela é mais difícil de ver e
> mais cara de manter: no lifecycle.

D fica **rejeitada**. Não por custo de convergência — por não entregar a propriedade que a justifica.

## VIII.20 Reexame da alternativa B — a latência é implementação, não arquitetura

A discovery rejeitou B por quatro danos. Reexaminando cada um sem o peso do custo:

**Dano 1 — latência de claim (~5 s).** O `poll-seconds=5` é uma propriedade do laço atual, não da
arquitetura. A verdade é a tabela `execution`; um sinal de "há trabalho novo" é apenas um despertar.
O PostgreSQL — que já é dependência obrigatória e já é a fonte da verdade — oferece `LISTEN/NOTIFY`,
e um `NOTIFY` emitido na mesma transação que insere a linha chega ao Worker em milissegundos. Se o
sinal se perder, o polling continua sendo a rede de segurança: **o sinal não é fonte de verdade, é
otimização de latência**, exatamente o critério que este documento exige. Alternativas equivalentes:
polling adaptativo (intervalo curto quando houve atividade recente) ou um endpoint de wake-up local.
**Dano refutado.**

**Dano 2 — perda de UX síncrona.** Uma resposta HTTP **não precisa** esperar o resultado, mas **pode**
— e esperar não é executar. A App enfileira, aguarda a conclusão por um curto orçamento de tempo
(observando a linha) e responde "renomeado" ou "ainda em andamento". A semântica interativa é
preservada sem que a mutação saia do Worker e sem segundo motor. **Dano refutado**, ao custo de um
mecanismo de espera na App.

**Dano 3 — explosão de execuções triviais.** Este dano é sobre *dar `Execution` a tudo*, e vale
igualmente para B e para D. É tratado em VIII.22, não é argumento entre alternativas.

**Dano 4 — recuperação de operações interativas abandonadas.** Uma execução de rename abandonada por
restart cai no mesmo tratamento das outras: não resumível, encerrada como `INTERRUPTED`, e a
divergência entre disco e catálogo é fechada pelo reconcile. Nada de novo. **Dano refutado.**

**O dano que sobrevive, e que a discovery não viu:** com B, **o Explorer para de funcionar se o Worker
estiver fora do ar**. Hoje o usuário renomeia mesmo sem Worker. É perda material de disponibilidade,
e é o único argumento honesto contra B.

Contra-argumento, também honesto: quando o Worker não sobe, o produto já perdeu inventário,
organização, conversão, deduplicação e quarentena. Que o rename continue funcionando é conveniência
residual, não capacidade preservada — e o Worker é supervisionado, reiniciado automaticamente e tem
seu estado visível na tela. A resposta correta a "o Worker caiu" é reiniciá-lo, não manter um segundo
motor permanentemente para o caso de ele cair.

**B, com sinalização de baixa latência e espera opcional na App, será chamada de B′ daqui em diante.**

## VIII.21 Alternativa C — derivada do código, não do desenho

Antes de comparar, uma terceira formulação, derivada da observação de que o código já separa
naturalmente **produtor**, **motor** e **leitor**:

**C — fronteira por papel, com o motor como serviço interno.** App e Worker deixam de ser papéis
arquiteturais e viram apenas *deployments*: existe um único **Execution Engine** que pode ser
embarcado em qualquer processo, e a regra passa a ser "o engine é o único que executa", sem dizer em
que JVM ele está. A configuração decide se há um engine na App, no Worker, ou nos dois.

C é atraente porque dissolve a pergunta — mas falha no mesmo ponto que D: **dois engines ativos são
dois agendadores**, e todos os problemas de coordenação de D reaparecem, agora sem sequer a regra que
limitava os casos. C só é coerente na configuração "exatamente um engine ativo" — que é B′ com outro
nome, ou é o modo `app-worker-combined`, que já existe e é explicitamente de desenvolvimento.

**C fica rejeitada** por não ser uma alternativa distinta: ou colapsa em B′, ou reintroduz D.
## VIII.22 `Execution` para tudo: cinco papéis, cinco perguntas

A `Execution` acumula cinco funções que não precisam andar juntas:

| Papel | O que dá | Quem precisa |
| --- | --- | --- |
| P1 **registro/auditoria** | "isto aconteceu, quando, com que resultado" | todo efeito E1, E2, E9 |
| P2 **unidade de posse** | um executor por vez, com lock e lease | todo efeito E1, E2 |
| P3 **unidade de progresso** | algo para a UI acompanhar | operação cuja duração o usuário percebe |
| P4 **unidade de recuperação** | o que reclamar e compensar após um crash | operação que pode morrer no meio |
| P5 **job assíncrono** | trabalho que sobrevive à requisição | operação que dura mais que uma requisição |

O medo de "explosão de execuções triviais" confunde P1/P2 com P5. **Uma operação pode merecer P1, P2
e P4 sem merecer P3 nem P5** — é o caso do rename: precisa de registro, de posse e de ser
recuperável, e não precisa de barra de progresso nem de experiência assíncrona.

Quantificando conceitualmente o volume: as operações que ganhariam `Execution` e hoje não têm são
rename, apagar-de-vez e a quarentena pelo Explorer, mais a purga de catálogo. Em uso doméstico, são
dezenas por semana — não milhares. **O risco real não é volume de linhas: é ruído no histórico**, e
isso é problema de apresentação, resolvido por filtro por tipo, que a tela de execuções já tem.

Conclusão: **P1+P2+P4 para todo E1/E2, sem exceção. P3 e P5 são decisões de UX por operação.** Isso é
o que permite a B′ preservar a UX síncrona sem abrir mão da disciplina.

## VIII.23 Classificação das operações pela nova responsabilidade

Sob a fronteira proposta. **INT** = dono da interação · **CMD** = quem produz o comando · **EXEC** =
onde o efeito deve rodar · **E** = categoria de efeito · **H1′** = exige disciplina.

| Operação | INT | CMD | EXEC (B′) | E | H1′ | Tensiona a fronteira? |
| --- | --- | --- | --- | --- | --- | --- |
| Organização — execute | App | App | Worker | E1 | sim | não |
| Organização — preview | App | App | **Worker** | E6 | **sim** | sim — resultado grande, ver VIII.26 |
| Undo | App | App | Worker | E1 | sim | não |
| Conversão | App | App | Worker | E1+E3 | sim | não |
| Dedup delete | App | App | Worker | E1 | sim | não |
| Quarentena — restore em lote | App | App | Worker | E1 | sim | não |
| Quarentena — restore unitário | App | App | **Worker** | E1 | sim | **sim** — UX síncrona |
| Quarentena — purga | App/scheduler | App | Worker | E1 | sim | não |
| Quarentena — limpeza de ausentes | App | App | **Worker** | E2 | sim | não |
| Explorer — rename | App | App | **Worker** | E1 | sim | **sim** — 3 ms, UX síncrona |
| Explorer — quarentenar | App | App | **Worker** | E1 | sim | não |
| Explorer — apagar de vez | App | App | **Worker** | E1 | sim | não |
| Reconcile | scheduler | App | Worker | E2 | sim | não |
| Inventário | App/watcher | App | Worker | E2 | sim | não |
| Purga de catálogo ausente | scheduler | App | **Worker** | E2 | sim | não |
| Troca de biblioteca | App | App | **App** | E9 | parcial | **sim** — ver VIII.26 |
| Backlogs (pHash, vídeo) | App/startup | App | Worker | E2 | sim | não |
| Similaridade (fotos, vídeos) | App | App | **Worker** | E6 | **sim** | **sim** — resultado é o produto |
| Rebuild de metadata | App | App | Worker | E2 | sim | não |
| Rebuild de localização | App | App | Worker | E2 | sim | não |
| Dataset geográfico | App/scheduler | App | Worker | E2+E3 | sim | não |
| Miniatura sob demanda | requisição | — | **App** | E3 | **não** | não |
| Atualização da aplicação | App | — | **App** | E8+E5 | não | não |
| Instalação de ferramenta | App | — | **App** | E8 | não | não |
| Backup / restore do catálogo | App | — | **App** | E8 | não | não |

Cinco operações tensionam a fronteira. Nenhuma a quebra; todas exigem uma resposta explícita, dada em
VIII.26.

## VIII.24 Teste da frase, e sua forma final

> "App = interaction/intent; Worker = execution/effects."

Onze tentativas de quebra:

| Situação | Quebra? | O que mostra |
| --- | --- | --- |
| App precisa produzir efeito de domínio | **sim** | miniatura sob demanda (E3): a resposta *é* o artefato. A frase precisa dizer **quais** efeitos |
| Worker precisa decidir algo da interação | não | nenhum handler decide; pré-condição inválida vira resultado |
| Operação sem usuário | não | scheduler e watcher são produtores de intenção; intenção não exige humano |
| Scheduler como produtor | não | já é o padrão correto do `ReconcileScheduler` |
| Resultado grande | **tensiona** | preview e similaridade: exige onde o resultado mora |
| Resultado incremental | não | `conversion_item_result` já resolve: linha por item |
| Execução de milissegundos | **tensiona** | rename: exige a distinção P1–P5 |
| Execução de horas | não | é o caso central |
| Operação só de leitura | **tensiona** | leitura com artefato entra em H1′; leitura efêmera não |
| Escrita só em workspace | não | E3 fora de H1′ |
| Altera configuração/processo | **sim** | update, ferramentas, backup: o Worker é subordinado |

**Forma refinada:**

> A App é dona da **interação e da intenção**, e da **manutenção da própria instalação**.
> O Worker é dono da **execução de todo efeito sobre a biblioteca e o catálogo do usuário**, e dos
> artefatos operacionais que outro processo consome.
> Efeitos derivados e regeneráveis (E3), configuração (E4) e cálculo puro (E7) não têm dono
> arquitetural: rodam onde forem necessários.

## VIII.25 Crash e recuperação como critério

Confrontando os modelos com nove eventos. **A** = estado atual, **B′** = recomendada, **D** = rejeitada.

| Evento | Estado atual | B′ | D |
| --- | --- | --- | --- |
| App morre durante mutação da App | efeito parcial, linha órfã `RUNNING` sem lease; reclaim do Worker **ignora** (sem lease); recuperação depende do startup da App | não existe mutação na App | efeito parcial; recuperação exige que a App tivesse lease — mecanismo novo |
| Worker morre | reclaim por lease expirado + `RECONCILE` compensatório | idem | idem, para a metade dele |
| PostgreSQL cai | mutação na App continua sem poder registrar; mutação no Worker falha e volta à fila | falha e volta à fila, uniformemente | dois comportamentos |
| Conexão do advisory lock cai | Worker: posse perdida → `INTERRUPTED`. App: **não percebe** | uniforme | uniforme só se a App implementar posse |
| Navegador fecha | operação da App continua sem ninguém para reportar | irrelevante: a verdade é a linha | idem |
| Usuário abandona a tela | idem | idem | idem |
| Worker reinicia | reclaim; o que estava na App não é afetado | reclaim, uniforme | reclaim de metade |
| App reinicia | `markInterruptedExecutions` — e é **V1**, que pode atingir execução viva | a App não executa: nada a interromper | V1 persiste e piora |
| Upgrade App/Worker durante execução | `SchemaCompatibility` recusa worker incompatível; a App não tem essa checagem para o que executa | uniforme | dois caminhos, uma checagem |

**B′ é a única em que a resposta a "o que acontece se X morrer" é a mesma para toda operação de
domínio.** É o critério mais forte a favor dela, e é de recuperabilidade — não de custo.

Registro: uma arquitetura que só funciona enquanto a requisição HTTP está viva é hoje o caso de cinco
operações (restore unitário, três do Explorer, limpeza de ausentes). Todas produzem efeito E1/E2.

## VIII.26 As cinco tensões, respondidas

**T1 — Rename do Explorer: 3 ms viram uma linha na fila?**
Sim, e a UX não muda: a App enfileira, aguarda a conclusão por um orçamento curto e responde
"renomeado". P3 (progresso) e P5 (assíncrono) não se aplicam; P1, P2 e P4 sim. O que se ganha:
registro, posse, self-write ancorado, e o fim do segundo motor.

**T2 — Restore unitário: e o diálogo?**
O diálogo termina antes. Se a pré-condição mudar entre a decisão e a execução (TOCTOU), a execução
termina com `CONFLICT`/`ORIGIN_MISSING` no resultado e a App reabre a pergunta com o estado novo.
**Não é preciso modelar uma "Execution aguardando input"** — e não se deve: uma execução parada
esperando um humano ocupa posse, segura locks e não tem prazo. Duas execuções curtas são melhores que
uma execução suspensa.

**T3 — Preview: o plano é grande.**
Responsabilidade e placement são decisões diferentes. Pela responsabilidade, o preview é E6 com
artefato consumido depois: **exige H1′**, logo o resultado precisa de casa durável — e isso vale
mesmo que continue rodando na App. Onde ele roda é a segunda decisão, e depende de medir heap. A
ordem correta é: primeiro persistir o plano, depois decidir o processo.

**T4 — Similaridade: o resultado é o produto.**
Idêntico ao preview, e mais agudo: o resultado é o que a tela pagina. Migrar sem decidir a casa do
resultado é impossível. Além disso, a exclusão mútua é hoje um `AtomicBoolean` de processo — que
**deixa de excluir qualquer coisa** no instante em que dois processos podem computar. É um defeito
latente que a fronteira expõe.

**T5 — Troca de biblioteca: E9.**
Cancela execuções, valida a pasta, limpa catálogo e cache. Precisa de registro e exclusão, mas é uma
transição de estado da instalação, não trabalho de domínio. **Fica na App**, com disciplina própria —
e é a única categoria em que a App legitimamente muta catálogo, porque o que ela está fazendo é
trocar de catálogo.
## VIII.27 Matriz de decisão

Pesos não somados de propósito: o que decide é o perfil, não a contagem.

| Critério | A (atual) | B′ (recomendada) | C | D |
| --- | --- | --- | --- | --- |
| Unicidade do motor | ✗ cinco capabilities com dois | **✓ um** | ✗ dois engines ativos | ✗ um handler, dois lifecycles |
| Consistência de garantias | ✗ nove garantias variam por caminho | **✓ uniforme** | ✗ | ✗ parcial |
| Recuperabilidade | ✗ cinco operações sem reclaim | **✓ uma resposta para tudo** | ✗ | ✗ metade coberta |
| Isolamento App/Worker | parcial | **✓ heap da UI livre de mutação** | ✗ | parcial |
| UX interativa | ✓ hoje é síncrona | **✓ preservada por espera** | ✓ | ✓ |
| Latência | ✓ imediata | ✓ com sinalização; ✗ sem ela | ✓ | ✓ |
| Testabilidade | ✗ dois caminhos por capability | **✓ um caminho** | ✗ | ✗ |
| Risco de divergência futura | ✗ alto e comprovado | **✓ baixo** | ✗ alto | ✗ **alto** — a duplicação fica escondida no lifecycle |
| Complexidade operacional | ✓ baixa | ✓ um processo executa | ✗ dois agendadores | ✗ dois agendadores |
| Complexidade conceitual | ✗ regra por caso | **✓ uma regra** | parcial | ✗ regra com três condições |
| Enforcement por teste | ✗ quase impossível | **✓ direto** | ✗ | ✗ nuance difícil |
| Comportamento em crash | ✗ varia | **✓ uniforme** | ✗ | ✗ varia |
| Adequação à Fase 5 | — | **✓** os sete workloads entram no mesmo molde | parcial | parcial |
| Disponibilidade sem Worker | ✓ Explorer funciona | **✗ não funciona** | ✓ | ✓ |
| Custo de convergência | zero | **alto** | alto | médio |

**Trade-off central:** B′ troca uma conveniência (o Explorer continuar funcionando com o Worker fora
do ar) por uniformidade completa de disciplina, recuperação e enforcement. É a única linha em que B′
perde, e é uma perda de disponibilidade residual num produto cujo Worker é supervisionado e
reiniciado automaticamente.

**Recomendação: B′.**

Registro explícito, conforme o critério desta fase: B′ é a alternativa **mais cara de convergir** e
ainda assim é a recomendada. O custo aparece uma única vez, na linha certa da matriz, e não foi usado
para escolher.

## VIII.28 Red team de B′ — sete contraexemplos do código atual

| # | Contraexemplo | Invalida? | Veredito |
| --- | --- | --- | --- |
| 1 | **Miniatura sob demanda** escreve no disco dentro da requisição e é E3 | não | fora de H1′ por taxonomia. Se um dia virar pré-geração em massa, aí é E6 com artefato e vai ao Worker |
| 2 | **Backup/restore do cluster** derruba as conexões do Worker | não | E8: o Worker é subordinado. **Exceção nomeada e justificada por dependência**, não por conveniência |
| 3 | **Instalação do ffmpeg** é pré-requisito do Worker | não | E8, dependência circular. Mesma exceção |
| 4 | **Atualização da aplicação** substitui o jar do supervisor | não | E8+E5. Mesma exceção |
| 5 | **Troca de biblioteca** muta catálogo na App | não | E9: transição de estado da instalação. Exceção nomeada, com disciplina própria |
| 6 | **`OperationLockService` é usado pela App hoje** (Explorer, restore) e continuaria a existir lá | não | lock é mecanismo, não efeito. A App pode **ler** o estado de lock; o que ela não pode é mutar sob ele |
| 7 | **`app-worker-combined`** roda os dois no mesmo processo | não | é deployment, não arquitetura: o trabalho continua indo à fila, ao claim, ao lock e ao lease. Confirma B′ em vez de contradizê-la |

**Cinco exceções ao todo, todas de E8/E9, todas justificadas por dependência ou por natureza — nenhuma
por peso, tamanho ou custo.** É o teste que a arquitetura precisava passar: se as exceções fossem
"porque é pequeno" ou "porque dá trabalho", a fronteira estaria mal definida.

## VIII.29 Contrato normativo provisório

Derivado da análise. Redigido para virar teste.

**APP MUST**
- ser a única origem de interação, validação, diálogo e resolução de ambiguidade;
- produzir um `Execution` + `request_payload` completo antes de qualquer efeito E1/E2 acontecer;
- ler progresso e resultado da base, nunca de memória de processo;
- supervisionar o ciclo de vida do Worker e da instalação (E5, E8);
- apresentar o estado do Worker quando ele estiver indisponível.

**APP MUST NOT**
- produzir efeito E1 ou E2 — inclusive em operações unitárias, curtas ou interativas;
- chamar diretamente um serviço de domínio mutador;
- criar uma `Execution` e executá-la ela mesma;
- ser a única testemunha de qualquer estado que a UI apresente.

**APP MAY**
- aguardar a conclusão de uma execução por um orçamento curto, para responder de forma síncrona;
- produzir artefatos derivados e regeneráveis (E3) dentro da requisição que os consome;
- escrever configuração (E4) e calcular (E7).

**WORKER MUST**
- reivindicar antes de executar, e executar somente o que reivindicou;
- manter lease enquanto executa;
- tomar os locks de todos os caminhos que vai tocar, antes de tocar em qualquer um;
- verificar a posse imediatamente antes de cada mutação irreversível;
- ancorar toda auto-escrita no `executionId`;
- persistir progresso e resultado;
- terminar sempre em estado terminal explícito;
- tratar pré-condição inválida como resultado, nunca como pergunta.

**WORKER MUST NOT**
- depender de HTTP, sessão, autenticação ou locale de requisição;
- resolver texto de usuário em idioma algum — mensagens são códigos;
- executar efeitos E5/E8;
- supervisionar a si mesmo.

**PRODUCERS** (controllers, schedulers, watchers) **MUST**
- limitar-se a produzir intenção: montar o comando e enfileirá-lo;
- responder com a referência da execução, não com o efeito.

**PRODUCERS MUST NOT**
- executar domínio, nem sequer "só desta vez porque é pequeno".

**EXECUTIONS MUST**
- existir para todo efeito E1, E2 e E9;
- carregar o comando completo em `request_payload`, versionado;
- ter exatamente um executor por vez, garantido por claim.

**HANDLERS MUST**
- ser um por `ExecutionType`, e um `ExecutionType` por handler;
- ser alcançáveis **somente** pelo dispatcher;
- declarar explicitamente `concurrencyLimit()` e `resumable()`.

**HANDLERS MUST NOT**
- ser chamados por controller, scheduler, runner ou outro serviço.

**SCHEDULERS MUST**
- produzir comando e enfileirar;
- decidir apenas *quando* pedir, nunca *como* executar.

**CONTROLLERS MUST NOT**
- chamar serviço mutador, abrir `Execution` ou ler estado vivo de runner.

**DOMAIN SERVICES MUST NOT**
- abrir a própria `Execution`;
- usar `AtomicBoolean` ou qualquer estado de processo como exclusão mútua — exclusão é lock
  persistente ou claim;
- manter em memória o progresso ou o resultado que a UI apresenta.

**PROGRESS / RESULT MUST**
- ser persistidos pelo executor e lidos pela App;
- sobreviver a restart de qualquer um dos processos;
- ser códigos localizáveis, nunca texto já resolvido.

**MAY** — o que permanece permitido: `@Async` na App para **operações E8/E9 e E3**, nunca para
domínio; cache em memória como otimização de leitura, desde que a verdade esteja na base e o cache
seja reconstruível.

## VIII.30 Decisões, por categoria

**A. Recomendadas com alta confiança**
1. Adotar **B′**: Worker é executor exclusivo de E1/E2, com sinalização de baixa latência e espera
   opcional na App para preservar UX síncrona.
2. Rejeitar **D**: recria dois lifecycles (VIII.19).
3. Taxonomia E1–E9 e **H1′** como critério de quem exige disciplina.
4. As cinco exceções E8/E9 (update, ferramentas, backup/restore, troca de biblioteca, miniatura sob
   demanda), nomeadas e fechadas.
5. `Execution` para todo E1/E2 nos papéis P1+P2+P4; P3/P5 por decisão de UX.
6. Não modelar "Execution aguardando input".

**B. Dependem de medição**
7. Onde o **preview** roda (heap sob plano grande) — a persistência do plano independe disso.
8. Orçamento de espera síncrona da App (quanto tempo antes de devolver "em andamento").
9. Qual mecanismo de wake-up (`LISTEN/NOTIFY`, polling adaptativo) — todos preservam a fonte de
   verdade; a escolha é operacional.

**C. Dependem de modelagem posterior (slices seguintes da 4.1)**
10. Onde mora o resultado da **similaridade** e do **preview** (tabela? formato? invalidação?).
11. Como a App espera pela execução sem segurar recurso (poll curto, `LISTEN`, timeout).
12. Se `CategoryConcurrency` precisa virar limite distribuído quando houver mais de um Worker.

**D. Defeitos ativos** (nenhum corrigido neste slice, por instrução)
13. **V1** — `markInterruptedExecutions` pode encerrar execução viva. Continua candidato a correção
    antes da Fase 5.
14. **V4** — similaridade computando dentro da requisição REST.
15. **Novo: V8** — `SimilarityGroupingRunner` usa `AtomicBoolean` como exclusão mútua; entre dois
    processos ela não exclui nada. Latente hoje, ativo assim que a similaridade for migrada.
16. **Novo: V9** — `PhotoSimilarityAsyncRunner.start` é `synchronized` no bean: mesma limitação.

**E. Dívida de convergência** (dimensionada, não usada para decidir)
17. Cinco operações E1/E2 executando na App (restore unitário, três do Explorer, limpeza de ausentes).
18. Purga de catálogo sem `Execution`.
19. Sete workloads da Fase 5 sem `Execution`.
20. Estado de UI em memória em seis runners.
21. Sinalização de wake-up e espera síncrona: mecanismo novo, pré-requisito da UX de B′.

**F. Precisam de decisão sua**
22. **Adotar B′?** É a mais cara de convergir e a recomendada por mérito.
23. **A UX do Explorer pode responder "em andamento"** quando a espera estourar o orçamento, ou o
    rename tem de ser sempre síncrono do ponto de vista do usuário?
24. **Perder o Explorer quando o Worker está fora do ar é aceitável?** É a única perda material de B′.
25. **A convergência entra antes da Fase 5, junto, ou depois?** A recomendação é: V1 e a persistência
    do resultado da similaridade **antes**; o resto junto.
26. **Os slices seguintes da 4.1** (4.1B em diante) devem modelar o resultado durável de
    preview/similaridade e o mecanismo de espera, ou isso vira parte da Fase 5?

## VIII.31 Registro de refutação

Nada da discovery foi reescrito. O que este slice mudou:

| Conclusão anterior (VIII.10/VIII.12) | Nova evidência | Conclusão substituta |
| --- | --- | --- |
| "Alternativa D é a recomendada: motor único, hospedeiro por regra" | O motor não é o handler: o dispatcher executa doze passos e o handler é o décimo. Hospedar na App perde nove das dez garantias, e preservá-las exige claim por id, lease na App e um limite por categoria distribuído — dois agendadores (VIII.19) | **D rejeitada. B′ recomendada** |
| "H2 produz dano em quatro casos" | Três dos quatro são propriedades da implementação atual, não da arquitetura: latência é polling, UX é espera, recuperação é reclaim. O quarto (explosão de execuções) é sobre `Execution` para tudo e vale para todas as alternativas | **Sobra um dano real: indisponibilidade do Explorer sem Worker** |
| "Uma capability pode ter mais de um ponto de entrada, mas um só motor — quando a UX exige, muda o hospedeiro" | A frase escondia que mudar de hospedeiro é mudar de lifecycle | **Muda quem espera, nunca quem executa** |
| "H1: efeito relevante sobre biblioteca/catálogo" | Faltavam E6 com artefato e E9; e E8 saía por conveniência, não por dependência | **H1′, com taxonomia E1–E9** |
| "Preview: primeiro disciplina, depois placement" | Confirmada, e reforçada: a disciplina que falta ao preview é a **casa do resultado**, não o processo | mantida, com o alvo explicitado |

---

# Fase 4.1B — Formalização da fronteira App × Worker

Direção decidida em 4.1A: **B′ — o Worker é o executor exclusivo do motor de execução**. Este slice
não reabre a decisão; formaliza-a a ponto de virar regra verificável, e a submete a uma varredura
nova do código. O custo de convergência aparece uma única vez, em VIII.44, depois da conclusão.

## VIII.32 App — definição formal

**A App é a fronteira com o usuário e com as intenções externas.** Tudo que ela faz é uma das cinco
coisas abaixo; o que não couber nelas não é dela.

### DEVE

| # | Responsabilidade | Definição operacional |
| --- | --- | --- |
| A1 | **Interação** | receber a requisição, autenticar, resolver locale e preferências, conduzir diálogos de uma ou mais rodadas |
| A2 | **Validação prévia** | tudo que pode ser decidido *antes* da submissão: forma, permissão, existência, coerência do pedido, e a detecção de condições que exigem pergunta (colisão de nome, pasta ausente) |
| A3 | **Intenção → trabalho durável** | transformar a decisão do usuário num comando completo e gravá-lo: linha `execution` + `request_payload` versionado |
| A4 | **Consulta e apresentação** | ler estado, progresso e resultado da base e renderizá-los; localizar códigos de mensagem |
| A5 | **Ciclo de vida da instalação** | supervisionar o processo Worker, o cluster embarcado, as ferramentas externas e a própria atualização |

### PODE

- **Aguardar** a conclusão de uma execução por um orçamento limitado, para responder de forma
  síncrona ao usuário. Aguardar é apresentação, não execução.
- Produzir artefatos derivados e regeneráveis para servir a própria resposta (uma miniatura).
- Escrever configuração, calcular, e manter caches de leitura **reconstruíveis**.
- Cancelar uma execução que ela mesma pediu.
- Orquestrar legitimamente: encadear *pedidos* (enfileirar A, e ao ver A terminar, enfileirar B).
  Orquestração é sequenciar intenções, nunca executar etapas.

### NÃO PODE

- Produzir efeito sobre a biblioteca ou o catálogo do usuário — **em nenhuma circunstância**,
  incluindo: operação unitária, operação de milissegundos, usuário esperando na tela, Worker
  indisponível, ou conveniência de implementação.
- Chamar um `ExecutionJobHandler`, direta ou indiretamente.
- Criar uma `Execution` e executá-la.
- Registrar uma `Execution` **depois** do efeito, para dar aparência de disciplina a algo que rodou
  sem ela.
- Ser a única testemunha de qualquer estado que a UI apresente.
- Usar estado de processo (`AtomicBoolean`, `synchronized`, mapa estático) como exclusão mútua de
  workload.

### O momento exato em que interação vira execução

> A interação termina quando o comando fica **completo**: todos os parâmetros resolvidos, todas as
> perguntas respondidas, nada mais dependendo do usuário. O teste é a **serializabilidade**: se o
> comando pode ser gravado e executado por outro processo sem perder informação, a interação acabou.

Esse instante é observável no código e independe de duração, tamanho ou de quem disparou.

## VIII.33 Worker — definição formal

**O Worker é o executor.** Ele recebe comandos completos e os realiza sob disciplina.

### DEVE

| # | Responsabilidade |
| --- | --- |
| W1 | reivindicar antes de executar, e executar apenas o que reivindicou |
| W2 | respeitar o limite de concorrência da categoria |
| W3 | tomar os locks de **todos** os caminhos que vai tocar, antes de tocar em qualquer um |
| W4 | estabelecer posse e mantê-la por lease renovado |
| W5 | verificar a posse imediatamente antes de cada mutação irreversível |
| W6 | ancorar toda auto-escrita no `executionId` |
| W7 | persistir progresso enquanto trabalha |
| W8 | persistir resultado, em forma que sobreviva ao seu próprio fim |
| W9 | terminar sempre em estado terminal explícito |
| W10 | aplicar a política de retry, reclaim e compensação declarada pelo tipo |
| W11 | observar cancelamento cooperativo em pontos seguros |

### PODE

- Recusar-se a começar (orçamento de tentativas esgotado, schema incompatível, lock ocupado).
- Devolver o trabalho à fila sem gastar tentativa quando o impedimento não é do trabalho.
- Ler configuração e escrever no workspace o que a execução precisa.

### NÃO DEVE CONTROLAR

- Quando o trabalho é pedido — isso é da App, mesmo quando o gatilho é um relógio.
- Se o trabalho deve ser pedido — validação prévia é da App.
- O próprio ciclo de vida, o do cluster, o das ferramentas externas ou o da atualização.
- Qualquer coisa que dependa de HTTP, sessão, autenticação ou locale.

### O que caracteriza trabalho do motor mesmo durando milissegundos

Três propriedades **conjuntas**, nenhuma delas temporal:

1. **produz efeito irreversível ou custoso de reverter** sobre dados do usuário;
2. **precisa de exclusão** contra outra operação sobre os mesmos caminhos;
3. **precisa ser explicável depois** — alguém pode perguntar "o que aconteceu com meu arquivo?".

Um rename satisfaz as três. Uma miniatura não satisfaz nenhuma.

## VIII.34 "Trabalho": definição por responsabilidade

Analisando cada natureza citada, e dizendo se é trabalho do motor:

| Natureza | É trabalho do motor? | Por quê |
| --- | --- | --- |
| Mutação de filesystem da biblioteca | **sim, sempre** | as três propriedades acima |
| Mutação de catálogo sobre a coleção | **sim, sempre** | irreversível na prática, precisa de exclusão, precisa ser explicável |
| Leitura longa **sem** artefato durável | não | nada a excluir, nada a explicar; é consulta cara |
| Leitura longa **com** artefato consumido depois | **sim** | o artefato é estado operacional: precisa de dono, prazo e casa |
| Leitura curta | não | consulta |
| Cálculo CPU-bound puro | não | sem efeito; onde roda é decisão de recurso |
| Processo externo | **depende do efeito** | ffmpeg convertendo = sim; ffprobe sondando = não |
| Produção de artefato durável | **sim** | ver leitura com artefato |
| Manutenção da instalação | não | o Worker é subordinado nela |
| Scheduler | **nunca** | scheduler é produtor de intenção, não executor |
| Operação administrativa da instalação | não | muda a premissa, não os dados |
| Infraestrutura da própria App/Worker | não | é pré-condição para haver motor |
| Alteração de configuração | não | reversível, barata, sem exclusão |
| **Operação composta: configuração + trabalho** | **decomposta** | a parte de configuração é da App; a parte de efeito vira comando enfileirado. Nunca se resolve mantendo tudo na App porque "está junto" |

A última linha é o critério que faltava para a troca de biblioteca e para o dataset geográfico.

## VIII.35 Taxonomia refinada — E1 a E10

A taxonomia de 4.1A resistiu à varredura com três correções:

| # | Categoria | Mudança | Exemplo real |
| --- | --- | --- | --- |
| E1 | Mutação da biblioteca | mantida | mover, renomear, apagar mídia |
| E2 | Mutação do catálogo da coleção | mantida | `deleteMissingBefore`, `applyRestore`, rebuild |
| E3 | Artefato derivado regenerável | mantida | miniatura, temporário de conversão |
| E4 | Configuração | mantida | `AppSetting`, preferências |
| E5 | Controle de processo | mantida | subir/derrubar Worker |
| **E6a** | **Leitura efêmera** | **dividida de E6** | listar pasta, contar arquivos, sondar vídeo |
| **E6b** | **Leitura que produz artefato durável** | **dividida de E6** | preview (plano), similaridade (grupos) |
| E7 | Cálculo puro | mantida | SSIM, distância de hash |
| E8 | Manutenção da instalação | mantida | ffmpeg, atualização, `pg_dump`/`pg_restore` |
| E9 | Transição de estado da instalação | mantida | troca de biblioteca |
| **E10** | **Infraestrutura de execução** | **nova** | bootstrap do cluster embarcado, criação do workspace, cache de settings, limpeza do workspace de conversão |

**Por que E6 precisou dividir.** Em 4.1A, "leitura com artefato" era uma nota de rodapé dentro de E6.
A varredura mostrou que a diferença é categórica: `FileExplorerService` lista uma pasta (efêmero, some
com a resposta) e `PhotoSimilarityService` produz um agrupamento que a tela pagina depois (durável em
intenção, hoje em memória). São regimes opostos de disciplina, e uma categoria que abriga os dois não
serve de regra.

**Por que E10 é nova.** O usuário pediu que "infraestrutura necessária para a própria App/Worker"
fosse considerada, e a varredura encontrou uma classe que a taxonomia anterior não classificava:
`ConversionWorkspaceCleaner` — um `ApplicationRunner` sob `@Profile(WORKER)` que apaga temporários
órfãos no arranque. Não é E3 (não serve a uma resposta), não é E8 (não é a instalação), não é
trabalho do motor (não tem comando, não tem quem peça). É **pré-condição para haver execução** — e
sua casa é o processo que executa, o que aliás já é o caso. E10 também abriga o bootstrap do
PostgreSQL embarcado, a criação do workspace e os caches de configuração.

**E10 é a única categoria cujo dono é "o processo que precisa dela"** — pode existir nos dois, sem
que isso configure segundo motor, porque não há capability de domínio envolvida.
## VIII.36 Matriz de garantias: qual categoria exige o quê, e por quê

Nem toda garantia serve a toda categoria. Cada uma existe por um motivo, e onde o motivo não se
aplica, exigi-la é cerimônia.

| Garantia | Existe para | Obrigatória em |
| --- | --- | --- |
| **Execution** | dar identidade, história e um lugar para o resultado | E1, E2, E6b, E9 |
| **claim** | garantir **um** executor por comando | E1, E2, E6b |
| **lease** | permitir descobrir abandono sem depender de quem morreu | E1, E2, E6b |
| **OperationLock** | impedir que duas operações toquem o mesmo caminho | E1, E2 (quando há caminho), E9 |
| **ownership** | ligar o direito de escrever a uma sessão viva | E1, E2 |
| **assertStillOwned no commit** | fechar a janela entre "eu tinha o direito" e "eu escrevi" | **E1 apenas** |
| **self-write** | impedir que o produto leia a própria escrita como alteração externa | E1 (e E3 quando dentro da árvore observada) |
| **cancelamento** | permitir desistir do que ainda não aconteceu | E1, E2, E6b **quando a duração é perceptível** |
| **retry** | distinguir falha do trabalho de falha do ambiente | E1, E2, E6b |
| **resultado persistente** | permitir que outro processo — ou outro dia — leia o que houve | E1, E2, E6b, E9 |
| **progresso persistente** | dar à UI algo para acompanhar sem depender de quem executa | quando a duração é perceptível |

**Leitura das duas linhas mais restritivas:**

- `assertStillOwned` **só** em E1. Uma mutação de catálogo já é transacional: se a posse caiu, a
  transação falha ou é revertida pelo banco. O que o banco não protege é o filesystem — daí a
  verificação existir exatamente antes do `SecureFileMove`, e não antes de cada `save`.
- **progresso é condicional à percepção, não à categoria.** Um rename não precisa de barra; um
  agrupamento de 8.000 candidatos precisa. Esta é a única linha da matriz em que UX decide, e é
  legítima porque progresso *é* apresentação.

**O que a matriz proíbe:** exigir `claim` de uma miniatura, exigir `lease` de uma mudança de
configuração, exigir `assertStillOwned` de um rebuild de metadata. Uniformidade que não serve a um
motivo é cerimônia, e cerimônia é o que faz uma regra ser contornada.

## VIII.37 Classificação completa do sistema real

Sob B′. **Exec. hoje** = onde roda · **Exec. B′** = onde deve rodar · **E** = categoria ·
**Exec?** = precisa de `Execution` · **Dur?** = resultado precisa ser durável · **Esp?** = a App pode
aguardar · **s/W** = pode existir sem Worker.

| Capability | Origem | Efeito | Exec. hoje | Exec. B′ | E | Exec? | Dur? | Esp? | s/W | Justificativa |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Inventário | tela, watcher, onboarding | catálogo | W | **W** | E2 | sim | sim | não | não | trabalho do motor |
| Reconcile agendado | scheduler | catálogo | W | **W** | E2 | sim | sim | não | não | idem |
| **Reconcile reativo** | watcher | catálogo | **A** | **W** | E2 | sim | sim | não | não | **V11**: mesmo efeito, segundo motor |
| Organização — execute | tela | biblioteca | W | **W** | E1 | sim | sim | não | não | trabalho do motor |
| Organização — preview | tela | artefato (plano) | A | **W** | E6b | sim | **sim** | sim | não | artefato consumido depois |
| Undo | tela | biblioteca | W | **W** | E1 | sim | sim | não | não | trabalho do motor |
| Conversão | tela | biblioteca + workspace | W | **W** | E1+E3 | sim | sim | não | não | idem |
| Dedup delete | tela | biblioteca | W | **W** | E1 | sim | sim | não | não | idem |
| Quarentena — restore lote | tela | biblioteca | W | **W** | E1 | sim | sim | não | não | idem |
| **Quarentena — restore unitário** | tela | biblioteca | **A** | **W** | E1 | sim | sim | **sim** | não | conversa termina antes; espera preserva UX |
| Quarentena — purga | tela, scheduler | biblioteca | W | **W** | E1 | sim | sim | não | não | trabalho do motor |
| **Quarentena — cleanup absent** | tela | catálogo | **A** | **W** | E2 | sim | sim | sim | não | muta catálogo em lote |
| **Explorer — rename arquivo** | tela | biblioteca | **A** | **W** | E1 | sim | sim | **sim** | não | as três propriedades de VIII.33 |
| **Explorer — rename pasta** | tela | biblioteca | **A** | **W** | E1 | sim | sim | **sim** | não | **V12**: `Files.move` cru, catálogo não reescrito |
| **Explorer — quarentenar** | tela | biblioteca | **A** | **W** | E1 | sim | sim | sim | não | mesmo efeito do dedup delete |
| **Explorer — apagar de vez** | tela | biblioteca | **A** | **W** | E1 | sim | sim | sim | não | efeito mais irreversível do produto |
| pHash backlog | startup, tela | catálogo | A | **W** | E2 | sim | sim | não | não | Fase 5 |
| Fingerprint de vídeo backlog | startup, tela | catálogo | A | **W** | E2 | sim | sim | não | não | Fase 5 |
| **Similaridade de fotos** | tela, **API** | artefato (grupos) | A | **W** | E6b | sim | **sim** | sim | não | resultado é o produto |
| **Similaridade de vídeos** | tela, **API** | artefato (grupos) | A | **W** | E6b | sim | **sim** | sim | não | idem |
| Rebuild de metadata | tela | catálogo | A | **W** | E2 | sim | sim | não | não | Fase 5 |
| Rebuild de localização | tela | catálogo | A | **W** | E2 | sim | sim | não | não | Fase 5 |
| Dataset geográfico | tela, scheduler | catálogo + workspace | A | **W** | E2+E3 | sim | sim | não | não | decomposto: baixar+importar é trabalho |
| **Purga de catálogo** | scheduler | catálogo | **A** | **W** | E2 | sim | sim | não | não | muta catálogo em massa |
| **Troca de biblioteca** | tela | premissa + catálogo | **A** | **A**¹ | E9 | sim | sim | n/a | sim | transição da instalação |
| Miniatura sob demanda | requisição | artefato regenerável | A | **A** | E3 | não | não | n/a | sim | a resposta *é* o artefato |
| Backup | tela | instalação | A | **A** | E8 | não² | sim | n/a | sim | Worker subordinado ao cluster |
| Restore de backup | tela | instalação | A | **A** | E8 | não² | sim | n/a | sim | derruba as conexões do Worker |
| Instalação de ferramentas | tela, bootstrap | instalação | A | **A** | E8 | não | sim | n/a | sim | dependência circular com o Worker |
| Update | tela, scheduler | instalação + processo | A | **A** | E8+E5 | não | sim | n/a | sim | substitui o jar do supervisor |
| PostgreSQL / bootstrap | arranque | infraestrutura | A | **A** | E10 | não | não | n/a | sim | pré-condição de tudo |
| Watcher | arranque | **nenhum**³ | A | **A** | — | não | não | n/a | sim | produtor de intenção |
| Workspace: criação | arranque | infraestrutura | ambos | **ambos** | E10 | não | não | n/a | sim | cada processo cria o que usa |
| Workspace: limpeza de conversão | arranque do W | infraestrutura | W | **W** | E10 | não | não | n/a | não | limpa o que o próprio Worker deixou |
| Cache de settings / rules | evento | infraestrutura | ambos | **ambos** | E10 | não | não | n/a | sim | reconstruível |

¹ **fica na App, mas decomposta** — ver VIII.38.
² merece registro (P1) por ser destrutivo, mas não é comando de fila: **decisão aberta**.
³ o watcher hoje **também executa** o reconcile reativo (V11) — na coluna B′ ele volta a ser só produtor.

**Quinze capabilities mudam de executor.** Nenhuma delas por peso: todas por produzirem E1, E2 ou E6b.

## VIII.38 Red team de B′ — doze exceções aparentes

| # | Caso | Contradiz B′? | Princípio que decide | Abre brecha? |
| --- | --- | --- | --- | --- |
| 1 | **Miniatura sob demanda** | não | **a resposta é o artefato**: sem consumidor posterior, não há estado operacional | não — o teste é "alguém lê isto depois?" |
| 2 | **Watcher** | não | é **produtor de intenção**; não executa | não |
| 3 | **Update** | não | **subordinação**: o Worker é filho do supervisor que seria substituído | não — o teste é a dependência, verificável |
| 4 | **Instalação de ferramentas** | não | **dependência circular**: o Worker precisa do ffmpeg que instalaria | não |
| 5 | **Backup / restore** | não | **subordinação**: o restore derruba as conexões do Worker | não |
| 6 | **PostgreSQL embarcado** | não | E10: pré-condição para existir motor | não |
| 7 | **Troca de biblioteca** | **parcialmente** | é E9 **composta**: cancelar execuções + validar + trocar configuração é da App; **limpar catálogo e cache é efeito e deveria ser comando** | **sim, se aceita inteira** — ver abaixo |
| 8 | **Workspace** | não | E10 | não |
| 9 | **Operação unitária do Explorer** | não | as três propriedades de VIII.33 se aplicam; **"é pequeno" foi recusado explicitamente** | não |
| 10 | **Restore unitário** | não | a conversa termina antes do comando | não |
| 11 | **Preview** | não | E6b: o artefato precisa de casa; onde roda é segunda decisão | não |
| 12 | **`app-worker-combined`** | não | é deployment, não arquitetura — VIII.39 | **sim, se usado como atalho** |

**Duas exceções exigem correção da própria formulação:**

- **Troca de biblioteca (7).** Aceitá-la inteira na App abriria a brecha "minha operação é
  administrativa, logo posso mutar". A resposta certa é a regra de VIII.34: **operação composta se
  decompõe**. A parte administrativa (cancelar, validar, apontar a nova pasta) é da App; a limpeza de
  catálogo e cache é efeito E2/E3 e deveria ser um comando. Fica classificada como **App com dívida
  de decomposição**, não como exceção limpa.
- **`app-worker-combined` (12).** Não é exceção enquanto for o que é hoje. Vira brecha no instante em
  que alguém disser "estamos no combined, posso chamar direto". VIII.39 fecha isso.

**As dez exceções restantes repousam sobre três princípios**, todos verificáveis e nenhum deles sobre
tamanho, velocidade ou custo:

1. **Subordinação** — o Worker não pode prover, substituir ou reiniciar quem o supervisiona (3, 4, 5).
2. **Sem consumidor posterior** — um artefato que morre com a resposta não é estado operacional (1).
3. **Pré-condição** — infraestrutura que precisa existir antes de haver motor (6, 8, e E10 em geral).

## VIII.39 `app-worker-combined`: mesma fronteira, mesma JVM

**Confirmada a preferência: a fronteira lógica é idêntica.** Não por conservadorismo — por três
razões de mérito:

1. **O combined existe para depurar o produto, não outro produto.** Se ele curto-circuitasse a fila,
   o desenvolvedor depuraria um caminho que não existe em produção, e o bug que ele procura pode ser
   justamente do claim, do lease ou do lock.
2. **Não há ganho.** O único custo que o curto-circuito evitaria é a latência do claim, que é o
   problema que o slice 4.1C vai resolver para todos os modos.
3. **Um curto-circuito é um segundo motor com autorização.** Seria a brecha perfeita: "no combined é
   assim", e daí a um `if (combined)` no caminho de produção é um passo.

Portanto: **mesmo processo, dois papéis lógicos.** A App submete, o Worker reivindica, o lock e o
lease funcionam (advisory locks do PostgreSQL não sabem nem se importam com quantos processos há).
O `NimbusProfiles` já documenta isso; a novidade aqui é que passa a ser **regra**, não observação.

**Consequência para enforcement:** os testes de composição devem afirmar que, no combined, o caminho
percorrido é o mesmo — inclusive que existe claim.

## VIII.40 Worker indisponível: comportamento formal

Sem fallback de execução na App, em nenhuma circunstância.

| Situação | O que a App faz |
| --- | --- |
| Worker no ar | tudo normal |
| Worker caído, supervisor reiniciando | **aceita e enfileira**; o comando fica `PENDING` e a UI diz "aguardando o processador" |
| Worker parado por incompatibilidade de schema | **aceita e enfileira**, mas a UI avisa que o processamento está parado e por quê |
| Worker permanentemente indisponível | idem — a fila é durável; a operação acontece quando ele voltar |
| Operação com espera síncrona e Worker fora | a espera estoura o orçamento; responde "em andamento", **nunca executa** |

**Quando rejeitar de imediato:** só quando o comando é inválido em si — pasta inexistente, item que
já saiu da quarentena, permissão insuficiente. Indisponibilidade do Worker **não** é motivo de
rejeição: rejeitar transformaria uma falha temporária de capacidade em perda de intenção do usuário.

**Como aparece:** a tela já mostra o estado do Worker (o supervisor o conhece). A regra nova é que
operações que hoje respondem "feito" passem a poder responder "na fila" — e que isso seja tratado
como estado normal, não como erro.

**O que continua funcionando sem Worker:** navegar, buscar, ver miniaturas, abrir o lightbox, mudar
configuração, trocar de biblioteca, fazer backup, atualizar, instalar ferramentas. **O que para:**
toda mutação de biblioteca e catálogo. É a perda material aceita em 4.1A, agora formalizada.
## VIII.41 Segundos motores — inventário completo

Varredura por nove padrões, não só `@Async`: chamada direta de serviço mutador, chamada de handler,
scheduler que executa, controller síncrono, `ApplicationRunner`, `ApplicationReadyEvent`, listeners,
exclusão por `AtomicBoolean`/`synchronized`, e `Execution` registrada depois do fato.

| # | Capability | Motor canônico | Segundo motor | Padrão que o revelou |
| --- | --- | --- | --- | --- |
| 1 | Mover para quarentena | `DuplicateDeletionJobHandler` | `ExplorerDeletionService.quarantineLocked` | serviço mutador chamado por controller |
| 2 | Restaurar da quarentena | `QuarantineRestoreJobHandler` | `QuarantineService.restore` | idem |
| 3 | **Reconciliar catálogo** | `ReconcileJobHandler` | `InventoryWatchService.automaticReconcile` → `reconcileAndApply` | **listener que executa + `Execution` post facto** |
| 4 | Agrupar semelhantes (fotos) | `SimilarityGroupingRunner` | `PhotoSimilarityService.groups()` na requisição | controller síncrono |
| 5 | Agrupar semelhantes (vídeos) | `SimilarityGroupingRunner` | `VideoSimilarityService.groups()` | idem |
| 6 | Apagar registros de catálogo | `ReconcileJobHandler` (parcialmente) | `CatalogFileRetentionService` via scheduler | scheduler que executa |
| 7 | Limpar registros de quarentena | — | `QuarantinePurgeService.cleanupAbsent` | serviço mutador chamado por controller |
| 8 | Renomear na biblioteca | — | `ExplorerRenameService` | serviço mutador chamado por controller |
| 9 | Apagar da biblioteca | — | `ExplorerDeletionService` (modo permanente) | idem |

**Exclusão mútua por estado de processo** — não é segundo motor, é a *impossibilidade* de exclusão
assim que houver dois processos:

| Onde | Mecanismo | Consequência sob B′ |
| --- | --- | --- |
| `SimilarityGroupingRunner` | `AtomicBoolean.compareAndSet` | **V8** |
| `PhotoSimilarityAsyncRunner.start` | `synchronized` no bean | **V9** |
| `FingerprintJobRunner` | `AtomicBoolean running` | mesma classe de defeito |
| `GeoDatasetAsyncRunner`, `MetadataRebuildAsyncRunner`, `LocationRebuildAsyncRunner`, `CatalogBackupAsyncRunner`, `ExternalToolInstallAsyncRunner`, `UpdateInstallAsyncRunner` | idem | idem — os três últimos são E8, onde o padrão é **legítimo** (um só processo por definição) |

Nota importante: para E8 a exclusão em memória **é correta**, porque a App é, por definição, o único
processo que executa aquilo. O defeito só existe onde a capability é de domínio.

**`Execution` registrada depois do fato** — padrão que o usuário pediu para procurar, e existe:
`ReconcileExecutionRecorder.recordIfRepaired` cria a linha **já concluída**, depois de o efeito ter
acontecido. Dá aparência de disciplina a uma operação que rodou sem nenhuma.

## VIII.42 Regras candidatas a enforcement

Escritas para serem verificáveis, com o mecanismo indicado. Nada implementado neste slice.

**Grupo 1 — motor único (ArchUnit, alta confiança)**

| R | Regra | Como verificar |
| --- | --- | --- |
| R1 | Nenhuma classe fora de `worker.application` chama `ExecutionJobHandler.handle` | ArchUnit: `noClasses().that().resideOutside("..worker.application..").should().callMethod(ExecutionJobHandler.class, "handle", ...)` |
| R2 | Nenhum controller depende de serviço que produza E1/E2 | ArchUnit sobre uma lista anotada ou por convenção de pacote |
| R3 | Todo `ExecutionType` tem exatamente um handler, e vice-versa | teste de contexto: mapear `List<ExecutionJobHandler>` e comparar com o enum |
| R4 | `SecureFileMove`, `Files.move`, `Files.delete` sobre caminho da biblioteca só a partir de handler | ArchUnit por origem de chamada; exige marcar as classes autorizadas |
| R5 | Nenhuma classe de `..infrastructure.web..` ou `..infrastructure.rest..` chama `executionRepository.save` | ArchUnit |

**Grupo 2 — composição por papel (teste de contexto, alta confiança)**

| R | Regra | Como verificar |
| --- | --- | --- |
| R6 | No perfil `worker`, nenhum bean de `..infrastructure.web..`/`..rest..` existe | subir o contexto e afirmar ausência |
| R7 | No perfil `app` isolado, `ExecutionDispatcher`, `WorkerLoop` e `LeaseRenewer` **não** existem | idem |
| R8 | No `app-worker-combined`, o caminho percorrido inclui claim | teste de integração afirmando `claim_count > 0` |

**Grupo 3 — disciplina de execução (teste próprio, confiança média)**

| R | Regra | Como verificar |
| --- | --- | --- |
| R9 | Todo handler declara `concurrencyLimit()` e `resumable()` explicitamente | reflexão sobre as implementações |
| R10 | Nenhum serviço de domínio tem campo `AtomicBoolean`/`synchronized` usado como exclusão | varredura de origem, com lista de exceções E8 nomeada |
| R11 | Nenhuma `Execution` é criada com `finishedAt` já preenchido | varredura de origem sobre `Execution.builder()` |
| R12 | Todo `announce` a partir de um handler passa `executionId` | varredura de origem |

**Grupo 4 — dependências de contexto (ArchUnit, alta confiança)**

| R | Regra | Como verificar |
| --- | --- | --- |
| R13 | Nenhum handler ou serviço alcançável por handler depende de `HttpServletRequest`, `Authentication` ou `LocaleContextHolder` | ArchUnit |
| R14 | Nenhuma classe de `..worker..` depende de `..infrastructure.web..` | ArchUnit |
| R15 | Nenhuma classe da App depende de `ExecutionDispatcher`, `WorkerLoop` ou `ExecutionReclaim` | ArchUnit |

**O que não é verificável hoje, e por quê:** "nenhum estado de UI existe só em memória" (R16
candidata) precisaria distinguir cache reconstruível de verdade — não há sinal sintático. Fica para
convenção de revisão, ou para uma anotação explícita (`@ReconstructibleCache`) se valer a pena.

## VIII.43 Impacto sobre a Fase 5

**Continuam corretos como previstos** (payload versionado, launcher, handler, progresso na linha):
pHash backlog, fingerprint de vídeo, rebuild de metadata, rebuild de localização, dataset geográfico.
Cinco dos sete.

**Mudam de definição:**

- **Similaridade (fotos e vídeos).** "Migrar o runner" **não é solução** enquanto o resultado for um
  `Map` da App: o Worker calcularia e o resultado morreria com ele. A Fase 5 só pode tocá-la depois de
  o resultado ter casa durável — e a mesma migração deve remover o cálculo síncrono da API REST, ou
  o segundo motor sobrevive à fase inteira.
- **Preview.** Entra na Fase 5 por classificação (E6b), coisa que o A8 não previa — ele o tratava
  junto da organização. O que ele precisa é a persistência do plano; onde roda é decisão posterior.

**Dependem de 4.1C/4.1D:** o mecanismo de espera da App (sem ele, as quinze capabilities que mudam de
executor pioram a UX), a sinalização de baixa latência, e o formato durável de plano e de grupos.

**Precisam obrigatoriamente ser corrigidos antes da Fase 5:**

| Achado | Por quê |
| --- | --- |
| **V1** | defeito ativo: corrompe o relato de execuções vivas |
| **V8/V9** | a exclusão por `AtomicBoolean` **deixa de excluir** no instante em que a similaridade tiver dois processos possíveis — migrar sem corrigir cria corrida real |
| **V11** | o reconcile reativo é um segundo motor do RECONCILE; migrar backlogs enquanto ele existe consolida o padrão |

**Podem convergir durante a Fase 5, sem arquitetura transitória perigosa:** V4 (cálculo síncrono na
API) junto da migração da similaridade; V5/V22 (purga de catálogo) como mais um comando; V2/V3/V12
(Explorer) como um bloco próprio, já que compartilham o mesmo motor de destino.

**Perigoso fazer parcialmente:** migrar a similaridade sem decidir o resultado; migrar um dos dois
backlogs; ou migrar o Explorer "só o quarentenar", deixando rename e apagar-de-vez na App — o motor
seria o mesmo e a disciplina, não.

## VIII.44 Custo de convergência

Depois da conclusão arquitetural, como manda o critério desta fase, e **sem influenciá-la**.

| Bloco | Alcance | Ordem de grandeza |
| --- | --- | --- |
| Mecanismo de espera + sinalização (4.1C) | infraestrutura nova, compartilhada | **grande** |
| Resultado durável de similaridade e preview (4.1D) | duas tabelas, invalidação, paginação | **grande** |
| Explorer (rename arquivo, rename pasta, quarentenar, apagar) | 2 serviços, 1 controller, 4 comandos novos | médio |
| Restore unitário + cleanup absent | 2 serviços, 2 comandos | médio |
| Reconcile reativo (V11) | 1 listener passa a enfileirar | pequeno |
| Purga de catálogo | 1 scheduler passa a enfileirar | pequeno |
| V1, V8, V9, V10, V12 | correções pontuais | pequeno |
| Fase 5 (sete workloads) | como previsto | grande |

**A arquitetura recomendada é a mais cara**, e a recomendação não muda por isso. O que o custo
informa é a **sequência**: nada que dependa da espera síncrona deve ser migrado antes de 4.1C, sob
pena de degradar a UX de forma visível e depois ter de refazê-la.

## VIII.45 Violações novas descobertas neste slice

| # | Violação | Gravidade |
| --- | --- | --- |
| **V10** | `ConversionExecutionRecorder.start(...)` é **código morto**: nenhum chamador desde que o launcher passou a criar a linha. Ainda assim constrói e persiste uma `Execution` completa — uma porta aberta para alguém "reusar" e abrir execução fora da fila | baixa (morta), média (armadilha) |
| **V11** | **Reconcile reativo executa na App**: `InventoryWatchService.automaticReconcile` chama `reconcileAndApply`, que muta o catálogo sob lock de árvore, e depois `ReconcileExecutionRecorder` cria a `Execution` **já concluída**. Segundo motor do `RECONCILE` com registro *post facto* | **alta** |
| **V12** | **Rename de pasta pelo Explorer** usa `Files.move` cru e **não reescreve o catálogo** — delega ao reconcile, que hoje roda pelo caminho de V11. Uma pasta renomeada deixa o catálogo inteiro daquele ramo desatualizado até que o watcher dispare | **média** |

V10–V12 somam-se a V1–V9. **Nenhuma foi corrigida neste slice**, conforme instrução.

## VIII.46 Registro de evolução

| Conclusão anterior | Onde | Nova evidência | Prevalece |
| --- | --- | --- | --- |
| Taxonomia E1–E9 | 4.1A/VIII.17 | `ConversionWorkspaceCleaner` não cabia em nenhuma; leitura efêmera e leitura com artefato exigem regimes opostos | **E1–E10, com E6 dividida em E6a/E6b** |
| "Cinco exceções, todas de E8/E9" | 4.1A/VIII.28 | a troca de biblioteca é **composta** e sua parte de efeito não é exceção legítima | **dez exceções sobre três princípios; troca de biblioteca vira dívida de decomposição** |
| "H1′ exige disciplina de E1, E2, E9 e E6-com-artefato" | 4.1A/VIII.17 | a disciplina não é monolítica: `assertStillOwned` só faz sentido em E1, progresso depende de percepção | **matriz de garantias por categoria (VIII.36)** |
| "Watcher é produtor de intenção" | 4.1A/VIII.24 | ele **também executa** o reconcile reativo | **é produtor com um segundo motor embutido (V11)** |
| "Cinco capabilities com dois motores" | 4.1A/VIII.6 | a varredura por nove padrões encontrou mais quatro | **nove capabilities** |
## VIII.47 Dependências para os próximos slices

**4.1C — mecanismo de espera e sinalização** precisa responder:

1. Como a App observa a conclusão sem segurar recurso: poll curto, `LISTEN/NOTIFY`, ou espera com
   timeout sobre a linha?
2. Qual o orçamento de espera, e ele é por operação ou global?
3. Como a UI representa "estourou o orçamento, continua na fila" sem parecer erro?
4. A sinalização é do produtor para o Worker (acordar o claim) ou também do Worker para o produtor
   (acabou)? Ambas mantêm a base como verdade, mas a segunda tem consumidor com sessão HTTP viva.
5. O `poll-seconds` continua existindo como rede de segurança? (a análise diz que sim)

**4.1D — resultados duráveis** precisa responder:

6. Formato do plano de organização: tabela de itens, documento, ou artefato em workspace referenciado
   pela linha?
7. Formato dos grupos de similaridade, com invalidação por assinatura de fingerprint (que já existe)
   e paginação — hoje feita em memória sobre uma lista.
8. Retenção: plano e grupos seguem o ciclo de vida da `Execution` ou têm o próprio?
9. O que acontece com uma consulta de similaridade enquanto o agrupamento ainda não existe — a
   pergunta que hoje é respondida computando na hora.

**Transversal:** a decomposição da troca de biblioteca (VIII.38, caso 7) precisa de dono; sugiro
4.1D, junto dos resultados duráveis, por também mexer em catálogo.

## VIII.48 Decisões abertas ao fim do 4.1B

**Confirmadas com alta confiança** (não precisam de nova decisão):
- B′ formalizada; definições de App e Worker; taxonomia E1–E10; matriz de garantias; dez exceções
  sobre três princípios; `app-worker-combined` preserva a fronteira; sem fallback de execução na App.

**Precisam de decisão sua:**

| # | Pergunta | Por que não posso decidir sozinho |
| --- | --- | --- |
| 1 | **Backup/restore merece `Execution`** (papel P1, registro) sem virar comando de fila? | é o único E8 destrutivo; a escolha é sobre o que o histórico deve contar |
| 2 | **A troca de biblioteca deve ser decomposta** — parte App, parte comando — ou aceita inteira na App como dívida registrada? | muda o escopo de 4.1D |
| 3 | **O rename do Explorer pode responder "na fila"** quando a espera estourar, ou precisa ser sempre síncrono do ponto de vista do usuário? | define se 4.1C precisa de garantia de latência ou só de melhor esforço |
| 4 | **A ordem de convergência do Explorer**: os quatro comandos juntos, ou primeiro os destrutivos (quarentenar, apagar) e depois os renames? | risco × valor, e você conhece o uso real |
| 5 | **O preview entra na Fase 5** (como E6b) ou fica para uma fase própria junto do resultado durável? | muda o tamanho da Fase 5 |
| 6 | **V11 (reconcile reativo) é corrigido antes da Fase 5** junto de V1? | é o segundo motor mais ativo do sistema — dispara sozinho a cada mudança no disco |

**Não decididas de propósito, por dependerem de modelagem** (4.1C/4.1D): mecanismo de espera, formato
dos resultados duráveis, retenção de artefatos, e se `CategoryConcurrency` precisa virar limite
distribuído quando houver mais de um Worker.

---

# Fase 4.1C — App × Worker Architecture

Fecha o **protocolo** de B′: como a intenção chega ao Worker rápido o bastante para não degradar a
UX, como a App observa a conclusão, e onde vivem os resultados. Continua sendo análise: nada
implementado, nenhum defeito corrigido.

## VIII.49 O protocolo, em uma página

```
 ┌── App ─────────────────────────────────────────────────────────────────┐
 │ 1. valida o que pode ser validado antes                                │
 │ 2. INSERT execution (PENDING) + request_payload      ── fonte da verdade│
 │ 3. COMMIT                                                              │
 │ 4. sinaliza "há trabalho"                            ── otimização     │
 │ 5. [opcional] aguarda por orçamento curto                              │
 └────────────────────────────────────────────────────────────────────────┘
                    │ sinal (perdível)          │ tabela (durável)
                    ▼                           ▼
 ┌── Worker ──────────────────────────────────────────────────────────────┐
 │ 6. acorda (pelo sinal) ou desperta sozinho (polling = safety net)      │
 │ 7. reserve → RUNNING + claimed_by + lease_until      ── UPDATE atômico │
 │ 8. categoria → locks → posse → contagem de tentativa                   │
 │ 9. handler                                                             │
 │10. progresso durável · resultado durável · estado terminal             │
 └────────────────────────────────────────────────────────────────────────┘
                    │ tabela (durável)
                    ▼
 ┌── App ─────────────────────────────────────────────────────────────────┐
 │11. viu terminal dentro do orçamento → responde o resultado             │
 │12. estourou o orçamento          → responde "em andamento" + id        │
 │13. em ambos os casos, a tela segue lendo o MESMO estado durável        │
 └────────────────────────────────────────────────────────────────────────┘
```

**Prova de motor único.** O caminho 7→10 é o único que existe: `ExecutionQueue.reserve` é um `UPDATE
… WHERE status = 'PENDING' … FOR UPDATE SKIP LOCKED … RETURNING`, isto é, **um passo atômico que
transiciona a linha e devolve o comando**. Não há como executar sem que essa transição aconteça, e
ela só acontece no dispatcher. Os passos 4, 5, 11 e 12 não tocam em `status`, `claimed_by`,
`lease_until` nem em qualquer efeito: **são leitura e sinal**. Portanto o protocolo acrescenta
*observação*, não um segundo caminho de execução — que é exatamente a propriedade que D não
conseguia entregar (VIII.19).

**Prova de que o sinal não é verdade.** Se o passo 4 falhar, se perder, ou se nunca existir, a linha
continua `PENDING` e o passo 6 a encontra pelo polling. O sinal só antecipa o passo 6.

## VIII.50 (A) Wake-up App → Worker

### Alternativas comparadas

| Opção | Latência | Fonte de verdade | Custo permanente | Veredito |
| --- | --- | --- | --- | --- |
| **Polling puro** (hoje, 5 s) | até 5 s | tabela | nenhum | insuficiente para interativo |
| **Polling adaptativo** (100 ms após atividade → 5 s ocioso) | ~100 ms | tabela | consultas ociosas mais frequentes | **viável, e sem mecanismo novo** |
| **PostgreSQL `LISTEN/NOTIFY`** | ~1–5 ms | tabela | uma conexão dedicada por processo ouvinte | **preferida** |
| Endpoint HTTP de wake-up App→Worker | ~1 ms | tabela | **o Worker precisaria de servidor web** | rejeitada: contradiz `web-application-type=none` |
| Arquivo/pipe no workspace | variável | tabela | dependência de filesystem entre processos | rejeitada: pior que NOTIFY em tudo |
| Fila em memória compartilhada | — | **memória** | — | **proibida**: seria segunda fila |

**Recomendação: `LISTEN/NOTIFY` como mecanismo principal, com polling adaptativo como
comportamento de fallback** — e o polling atual permanecendo como rede de segurança final.

### Onde emitir, e a relação com o commit

O ponto crítico: `NOTIFY` no PostgreSQL é **transacional** — a mensagem só é entregue no commit. Isso
dá de graça a propriedade exigida ("persistência primeiro, sinalização depois"): emitir o `NOTIFY` na
**mesma transação** do `INSERT` significa que ou os dois acontecem, ou nenhum. Não existe janela em
que o sinal chegue antes da linha existir.

O lugar natural é o `ExecutionEnqueueService`, que já é o único ponto por onde uma intenção vira
linha. Emitir de qualquer outro lugar seria criar um segundo produtor.

### Riscos analisados

| Risco | Resposta |
| --- | --- |
| **Lost wake-up** (ouvinte desconectado no instante do NOTIFY) | a linha continua `PENDING`; o polling a encontra. É por isso que o polling **não pode ser removido** |
| **Reconexão** | ao reconectar, o ouvinte deve **varrer a fila uma vez** antes de voltar a esperar: o que chegou durante a desconexão não é reenviado |
| **Restart do Worker** | o arranque já faz `reclaimAbandoned` e entra no laço; a primeira volta do laço é a varredura |
| **Vários `WorkerLoop`s** | hoje são N threads virtuais (`maxConcurrent`). Um `NOTIFY` acorda o processo; a distribuição entre loops continua sendo o `SKIP LOCKED`, que já garante que dois nunca pegam a mesma linha |
| **Vários trabalhos juntos** | `NOTIFY` coalesce mensagens idênticas na mesma transação; e como o sinal é só "olhe a fila", perder duplicatas é irrelevante |
| **Payload no sinal** | **não deve haver**. Um payload transformaria o canal em transporte de comando — e comando é a linha. O sinal carrega no máximo o `execution_type`, e mesmo isso é otimização discutível |
| **`app-worker-combined`** | o `NOTIFY` funciona igual (é o banco que entrega); e mesmo que se optasse por um evento in-process, ele teria de disparar **depois do commit** e sem pular o claim — ver VIII.57 |

### O que o wake-up não pode virar

- Não pode carregar o trabalho (seria segunda fila).
- Não pode ser condição para executar (seria fonte de verdade).
- Não pode ter confirmação de entrega (seria protocolo, e protocolo tem estado).

## VIII.51 (B) Espera síncrona limitada

### O modelo

**Quem espera:** a thread que atende a requisição, ou — melhor — um `DeferredResult`/`CompletableFuture`
do Spring MVC, que **libera** a thread do contêiner enquanto espera. Bloquear uma thread de servlet
por segundos é o padrão que mais rápido derruba um servidor sob carga, e aqui não há motivo para
pagá-lo.

**O que é esperado:** a transição da linha para um estado terminal. Nada mais — nem o resultado, que
é lido depois, da mesma forma que a tela leria.

**Como detectar conclusão:** duas formas, na ordem de preferência:

1. **`LISTEN` de conclusão** (ver VIII.52) acordando a espera;
2. **poll curto com backoff** sobre a linha (por exemplo 25 ms → 50 → 100 → 200, teto de ~250 ms),
   que em uma operação de 3 ms responde na primeira ou segunda tentativa.

O poll com backoff sozinho já é suficiente e não exige mecanismo novo. É a recomendação mínima.

### Regras da espera

| Questão | Decisão |
| --- | --- |
| A thread HTTP pode esperar? | **Não bloqueando.** Use resposta assíncrona; o contêiner fica livre |
| Timeout | orçamento curto, na ordem de **1 a 3 segundos** — tempo suficiente para operações unitárias e curto o bastante para não parecer travamento |
| Requisição cancelada / navegador fechado | a espera morre; **a execução continua**. Nada de cancelamento implícito |
| Restart da App durante a espera | idem: a linha está no banco, o Worker segue |
| Worker indisponível | a espera estoura; responde "em andamento" — **nunca executa** |
| Worker ocupado | idem |
| Terminou exatamente na fronteira do timeout | a resposta pode dizer "em andamento" para algo já concluído. **Isso é aceitável e não é erro**: a tela consulta o mesmo estado durável e corrige em milissegundos. A alternativa — esticar o timeout — só move a fronteira |

### O que a espera não é

Não é sincronização de execução: nada no Worker sabe que alguém espera. Não é garantia: o orçamento é
melhor esforço. E não é ponto de decisão: **a App nunca decide executar porque a espera estourou.**

## VIII.52 (C) Worker → App: necessário?

Três desenhos possíveis:

| Desenho | Latência da resposta | Complexidade | Verdade |
| --- | --- | --- | --- |
| **Só poll curto na App** | 25–250 ms após o fim | nenhuma nova | tabela |
| **`NOTIFY` de conclusão + espera local** | ~1–5 ms após o fim | uma conexão ouvinte na App + registro de esperas | tabela |
| Push do Worker para a App (HTTP/WebSocket) | ~1 ms | canal novo, autenticação, reconexão | **risco de virar verdade** |

**Recomendação: começar sem sinal Worker → App.** O poll curto com backoff entrega 25–250 ms, o que é
imperceptível para uma operação interativa; o `NOTIFY` de conclusão economizaria no máximo ~200 ms e
custa uma conexão dedicada mais um registro de esperas em memória na App — memória que, se alguém
confiar nela, vira exatamente o problema que estamos eliminando.

**Se um dia for adotado**, a regra é a mesma do wake-up: o sinal apenas acorda uma espera que, ao
acordar, **lê a linha**. Nunca entrega estado. E a espera deve funcionar sem ele.

**Push para o browser (SSE/WebSocket)** é assunto de UX, não deste protocolo: notifica a tela para
que ela recarregue do servidor. Continua valendo que o servidor lê a base.

## VIII.53 (D) Resultado durável: cinco camadas e critérios

| Camada | O que é | Onde vive hoje | Regra |
| --- | --- | --- | --- |
| 1. **Estado** | `PENDING`/`RUNNING`/terminal, claim, lease | colunas da `execution` | sempre na linha |
| 2. **Progresso** | contadores, item corrente, mensagem | colunas da `execution` | na linha; nunca em memória |
| 3. **Resultado resumido** | quantos moveram, pularam, falharam | colunas da `execution` | na linha |
| 4. **Resultado por item** | uma linha por arquivo, com desfecho | `conversion_item_result`, `movement`, `execution_error` | **tabela própria** |
| 5. **Artefato de leitura** | plano, agrupamento — consumido depois, paginado | hoje **em memória** | **tabela própria, com identidade e validade** |

### Critérios objetivos

**A `Execution` sozinha basta quando** o resultado é um punhado de contadores e uma mensagem, e
ninguém precisa saber *qual* item teve qual desfecho.

**Precisa de tabela associada quando** ao menos uma destas é verdadeira:
- o usuário pode perguntar "o que aconteceu com **este** arquivo?";
- o resultado é paginado;
- o resultado é parcial e cresce durante a execução;
- o resultado precisa sobreviver à execução que o produziu.

**O resultado pertence ao domínio, e não ao motor, quando** ele continua sendo verdade depois de a
execução ser esquecida. `movement` é do domínio (a quarentena depende dele para restaurar);
`conversion_item_result` é do motor (é o relatório daquele lote). O teste: *apagar a execução apaga
uma capacidade do produto?* Se sim, é domínio.

**Retenção.** Resultado do motor segue a `Execution` (cascade, como `conversion_item_result` já faz).
Resultado de domínio tem ciclo próprio. Artefato de leitura (camada 5) segue a **validade**, não a
execução — um agrupamento continua útil enquanto a assinatura dos fingerprints não mudar, mesmo que a
execução que o produziu seja antiga.

**Idempotência/retry.** Um retry precisa poder reescrever o resultado sem duplicar: chave natural por
`(execution_id, item)` para a camada 4; para a camada 5, uma nova execução **substitui** o artefato
anterior da mesma chave de validade.

**Resultado parcial e crash.** A camada 4 é escrita item a item, então um crash deixa o que foi feito
— é o que a conversão já faz e é o comportamento correto. A camada 5 é escrita ao final: um crash não
deixa artefato pela metade, e a próxima execução o produz inteiro.

**JSON genérico na `Execution`:** proibido como substituto de modelagem. Admissível apenas para o
*comando* (`request_payload`, que é entrada versionada) — nunca para o resultado.
## VIII.54 (E) Similaridade: modelo de resultado durável

O caso mais crítico. Hoje: `groups()` computa O(n²) sobre até 8.000 candidatos e guarda
`Map<Integer, CachedGroups<T>>` — chaveado pelo **limiar** —, com invalidação por **assinatura de
fingerprint**. A tela pagina sobre a lista em memória. Há dois motores (runner e requisição), e a
exclusão é `AtomicBoolean` (V8/V9).

### Alternativas de persistência

| # | Modelo | Como fica | Problema |
| --- | --- | --- | --- |
| M1 | **JSON do agrupamento na `Execution`** | uma coluna | paginar exige carregar tudo; não dá para filtrar; viola VIII.53 |
| M2 | **Tabela por execução**: `similarity_group` + `similarity_group_member` | grupos e membros normalizados, FK para a execução | resultado morre com a execução; um novo agrupamento igual recomputaria |
| M3 | **Tabela por validade**: mesma estrutura, chaveada por `(media_type, min_similarity, signature)` | o artefato sobrevive à execução e é reutilizado enquanto a assinatura valer | precisa de política de substituição e limpeza |
| M4 | **Materialização incremental** (grupos atualizados a cada novo fingerprint) | sem execução de agrupamento | muda o algoritmo; O(n²) não é incrementalizável trivialmente |

**Recomendação: M3.** Razões, nesta ordem:

1. **A validade já existe no domínio.** `SimilarityGroupCache` já invalida por assinatura de
   fingerprint — o conceito está pronto, só está em memória. M3 é a mesma semântica com casa durável.
2. **Reuso é a razão de o cache existir.** Com M2, reabrir a tela depois de a execução ter sido
   removida por retenção recomputaria tudo. Com M3, o artefato é do domínio enquanto for válido.
3. **Paginação e ordenação viram consulta**, com índice — em vez de `subList` sobre uma lista.

### Estrutura mínima

- `similarity_grouping` — a chave de validade: `media_type` (PHOTO/VIDEO), `min_similarity`,
  `fingerprint_signature`, `computed_at`, `execution_id` (quem produziu), `group_count`.
- `similarity_group` — pertence a um `similarity_grouping`: `similarity_percent`, `wasted_bytes`,
  `keep_media_id`, ordem.
- `similarity_group_member` — `group_id`, `media_id`, papel (`KEEP`/`DELETE`/`REVIEW`).

Foto e vídeo compartilham a estrutura; o que difere é como o grupo é formado (SSIM × multi-frame) e o
que já está encapsulado em `SimilarityGrouping`.

### Volume esperado

Teto atual de 8.000 candidatos. No pior caso realista — muitos quase-duplicados — a ordem é de
milhares de grupos e algumas dezenas de milhares de membros por combinação
`(tipo, limiar, assinatura)`. É volume trivial para o PostgreSQL; a preocupação real não é tamanho, é
**quantas combinações** de limiar guardar. Mitigação: reter apenas o agrupamento mais recente por
`(tipo, limiar)` e apagar os de assinatura vencida — a mesma regra que o cache já aplica ao esquecer.

### Comportamento nas bordas

- **Resultado parcial:** o agrupamento é publicado ao final. Enquanto não existir, a tela mostra
  "calculando" com o progresso da execução — que hoje ela já sabe fazer.
- **Cancelamento:** não publica; o agrupamento anterior válido, se houver, continua servindo.
- **Retry:** recomputa e substitui pela chave de validade; sem duplicata possível.
- **Consulta enquanto não há agrupamento:** **não computa na hora.** Responde "ainda não calculado" e
  oferece calcular — que é enfileirar. Esta é a mudança de contrato que mata V4, e ela é
  arquitetural, não estética: computar dentro da requisição é o segundo motor.

### Recomendação explícita antes da Fase 5

> **M3 deve existir antes de a similaridade ser migrada.** Migrar o runner com o resultado em memória
> produziria um Worker que calcula e joga fora. E V8/V9 devem morrer no mesmo passo: com o resultado
> na base, a exclusão passa a ser o claim, e o `AtomicBoolean` não tem mais função nenhuma.

## VIII.55 (F) Preview de organização

Respondendo às sete perguntas separadamente:

| # | Pergunta | Resposta |
| --- | --- | --- |
| 1 | Quem é responsável por produzir o plano? | **o motor** — é E6b: leitura que produz artefato consumido depois |
| 2 | Em qual processo o cálculo deve acontecer? | **Worker**, por consequência de (1); não por ser pesado |
| 3 | O resultado precisa sobreviver à requisição? | **sim** — a tela pagina o plano em requisições seguintes |
| 4 | O usuário pode executar depois o plano apresentado? | **não como está**: `execute` **recalcula** o plano. O plano exibido é evidência para decidir, não entrada da execução |
| 5 | Risco de ficar stale entre preview e execução? | **sim, e é aceito por desenho** — o recálculo é o que impede executar um plano velho |
| 6 | Que identidade liga preview e execução? | hoje **nenhuma**: são duas `Execution` independentes do tipo `ORGANIZATION`, uma com `executeFlag=false` |
| 7 | Persistir ou recalcular? | **persistir o plano** (para paginar e sobreviver ao restart) e **continuar recalculando na execução** |

**Recomendação arquitetural:** preview vai ao Worker como qualquer E6b, e o plano ganha casa durável
com o mesmo desenho de camada 5 (VIII.53) — chave de validade sendo o comando de preview
(origem, destino, layout, filtros) mais um carimbo de tempo.

**A decisão de (4) merece ser explicitada como contrato**: o plano é *evidência*, não *comando*. Se um
dia se quiser "executar exatamente este plano", isso vira um comando novo, com o plano como entrada —
e aí o problema de stale reaparece e precisa de revalidação item a item. Não é o desenho de hoje e
não deve ser adotado sem essa análise.

**Benchmark necessário** (para dimensionar, não para decidir): heap ocupado e tempo de produção do
plano para 100 mil e 500 mil itens, e o custo de persistir o mesmo plano. Serve para escolher entre
persistir itens em tabela ou um artefato em workspace referenciado pela linha; não muda quem é o
responsável.

## VIII.56 (G) Explorer: as cinco mutações modeladas

Regra comum: `App valida → comando durável → Worker → resultado durável → resposta imediata ou "em
andamento"`.

| Operação | Validação prévia possível na App | Comando | O que o Worker revalida | Resultado |
| --- | --- | --- | --- | --- |
| **Rename de arquivo** | nome válido, pai existe, **destino livre**, item é físico | caminho + nome novo | destino livre sob lock; origem ainda é o mesmo arquivo | linha + catálogo reescrito |
| **Rename de pasta** | idem | caminho + nome novo | idem | linha + **catálogo do ramo reescrito** (hoje não é: **V12**) |
| **Enviar para quarentena** | pasta/arquivo existe, há raiz de quarentena, há itens catalogados | caminhos + raiz | cada item ainda ativo e ainda no lugar | `movement` por item (já existe) |
| **Apagar de vez** | existe, está dentro da biblioteca, contagem para confirmação | caminhos | idem | linha + itens afetados |
| **Restore** (quarentena) | item ainda em quarentena, origem existe, destino livre, resolução escolhida | movimento + destino + resolução | as mesmas pré-condições sob lock | resultado por item |

### O conflito é serializável? — verificado no código

**Sim, nas cinco.** A evidência:

- `ExplorerRenameService`: a checagem `Files.exists(target)` acontece **antes** do lock e produz uma
  **recusa** (`renameTargetExists`), não uma pergunta em meio à mutação. O usuário digita outro nome e
  reenvia — duas requisições, comando completo na segunda.
- `QuarantineService.restoreOne`: conflito e origem ausente viram `CONFLICT`/`ORIGIN_MISSING` **no
  resultado**; o diálogo acontece depois, e o reenvio traz `ConflictResolution` no comando.
- `ExplorerDeletionService`: as decisões (nada catalogado, pasta vazia, itens presentes) são todas
  tomadas antes de mover, e todas produzem recusa ou seguem em frente.

**Não encontrei nenhum caso em que a mutação precise ser interrompida para perguntar algo.** O padrão
real é sempre: detectar → recusar/reportar → o usuário decide → novo comando completo.

**TOCTOU** é tratado pela revalidação no Worker sob lock: se o destino deixou de estar livre entre a
validação e a execução, a execução termina com o mesmo `CONFLICT` que a App já sabe apresentar.

## VIII.57 (I) `app-worker-combined`

**Mesmo caminho, sem exceção.** Como garantir estruturalmente:

1. **Não existe API para pular.** O único jeito de sair de `PENDING` é `ExecutionQueue.reserve`, que é
   `@Profile(WORKER)` por estar no dispatcher. Se o combined ativa `worker`, ele tem o dispatcher —
   e é ele quem reivindica.
2. **O wake-up in-process, se existir, dispara depois do commit** (`TransactionSynchronization`
   `afterCommit`) e chama exatamente o mesmo `dispatchOne()` — acorda, não executa.
3. **Nada pode ser condicionado ao modo.** Um `if (combinedProfile)` em caminho de produção é a
   brecha; a regra é que nenhuma classe de domínio ou de motor consulte perfis ativos.

**Como provar por teste:** um teste de integração no perfil combinado que enfileira uma operação e
afirma, ao final, que a linha tem `claimed_by` preenchido e `claim_count >= 1`. Se alguém introduzir
um atalho, esses dois campos ficam nulos e o teste quebra. É a asserção mais barata e mais difícil de
burlar que encontrei.

## VIII.58 (H) Worker indisponível: estado e UX

**Distinguir "esperando normalmente" de "sem executor" precisa de dado, não de suposição.** O que já
existe na linha permite quase tudo:

| Sinal | De onde vem | O que indica |
| --- | --- | --- |
| `PENDING` + `available_at` no passado | fila | trabalho pronto para ser pego |
| Nenhuma linha `RUNNING` **e** fila crescendo | consulta | ninguém está pegando |
| `claim_count` alto sem terminar | fila | poison job, não falta de executor |
| `WorkerSupervisor.isRunning()` | processo | há processo vivo — mas só a App que o supervisiona sabe |
| **Faltando** | — | **um batimento do Worker na base** |

**Achado:** hoje a saúde do Worker é conhecida apenas pelo `WorkerSupervisor`, em memória, e **não é
exposta a lugar nenhum** (`grep` não encontra consumidor). No `app-worker-combined` e num cenário
futuro com Worker remoto, isso não serve.

**Recomendação:** um batimento durável — o Worker registrando periodicamente que está vivo e o que
está fazendo (o `LeaseRenewer` já escreve a cada rodada; falta um registro por *worker*, não por
execução). Com ele, a App distingue as cinco situações pedidas:

| Situação | Como a App sabe | O que a UI diz |
| --- | --- | --- |
| Worker iniciando | batimento recente, ainda sem claim | "preparando o processador" |
| Worker reiniciando | batimento parou há pouco, supervisor tentando | "reiniciando" |
| Schema incompatível | o Worker sai com código próprio e não bate mais | **"processamento parado: versão incompatível"** |
| Falhas repetidas | supervisor com contador alto | "processamento indisponível" |
| Fila crescendo sem consumidor | pendentes antigas + sem batimento | "N pedidos aguardando" |

**Em nenhuma delas a App executa.** Ela aceita, enfileira e informa. Rejeitar de imediato só quando o
comando é inválido em si.

## VIII.59 (J) Enforcement: o que cada ferramenta prova

Separando por capacidade real, que é onde a proposta anterior era otimista:

**ArchUnit prova estaticamente** (alta confiança):

| Regra | Formulação |
| --- | --- |
| J1 | nenhuma classe fora de `..worker.application..` chama `ExecutionJobHandler.handle` |
| J2 | nenhuma classe de `..infrastructure.web..`/`..rest..` chama `ExecutionQueue`, `ExecutionDispatcher` ou `executionRepository.save` |
| J3 | nenhuma classe de `..worker..` depende de `..infrastructure.web..`, `HttpServletRequest`, `Authentication` ou `LocaleContextHolder` |
| J4 | nenhuma classe anotada com um marcador de "serviço mutador" é acessada por controller |
| J5 | `Files.move`, `Files.delete`, `Files.write` só a partir de um conjunto declarado de classes |
| J6 | nenhum `@Async` novo em pacote de domínio |

**O ponto fraco de J4/J5**: exigem marcar as classes autorizadas. Sem marcação, ArchUnit não sabe
distinguir "mutar a biblioteca" de "escrever no workspace" — os dois são `Files.write`. **Proposta: uma
anotação de arquitetura** (por exemplo `@LibraryMutation`) nos poucos pontos legítimos, e a regra
passa a ser "quem chama `Files.*` de mutação sem estar marcado, falha". A anotação vira parte do
contrato, não decoração.

**Teste de contexto prova** (alta confiança): J7, composição por perfil — o que existe em `app`, em
`worker`, e que no combinado existem os dois. J8, um handler por `ExecutionType` e vice-versa.

**Teste de integração prova** (média): J9, no combinado, uma operação enfileirada termina com
`claimed_by` e `claim_count >= 1` (VIII.57). J10, nenhuma `Execution` nasce com `finished_at`.

**Só auditoria semântica pega** (aceitar e registrar): se um resultado necessário à UI está apenas em
memória; se um cache é reconstruível; se uma capability ganhou um segundo ponto de entrada
*conceitual* sem repetir código. Para estes, a defesa é revisão — e o que este documento oferece é a
lista de padrões a procurar (VIII.41).
## VIII.60 (K) Matriz estado atual × estado-alvo

**Coord.** = mecanismo de coordenação · **2º motor?** = existe segundo caminho hoje.

| Capability | Executor hoje | Alvo | 2º motor? | Resultado hoje | Resultado alvo | Coord. hoje | Coord. alvo | Violação | Slice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **RECONCILE reativo** | App (watcher) | **Worker** | **sim** (`ReconcileJobHandler`) | linha *post facto* | linha da execução | lock de árvore | claim+lock | **V11** | **4.2** |
| **Explorer rename arquivo** | App | **Worker** | sim (nenhum handler ainda) | nenhum | linha + catálogo | lock | claim+lock+posse | V3 | 4.2 |
| **Explorer rename pasta** | App | **Worker** | sim | nenhum, catálogo **não** reescrito | linha + catálogo do ramo | lock | claim+lock+posse | **V12** | 4.2 |
| **Explorer quarentena** | App | **Worker** | **sim** (`DuplicateDeletionJobHandler`) | `Execution` sem claim | linha + `movement` | lock | claim+lock+posse | V3 | 4.2 |
| **Explorer delete permanente** | App | **Worker** | sim | **nenhum** | linha + itens | lock | claim+lock+posse | **V2** | 4.2 |
| **Restore unitário** | App | **Worker** | **sim** (`QuarantineRestoreJobHandler`) | contadores na linha | resultado por item | lock | claim+lock+posse | V3 | 4.2 |
| **Cleanup de quarentena** | App | **Worker** | não | contadores na linha | idem | lock | claim | — | 4.2 |
| **Catalog purge** | App (scheduler) | **Worker** | parcial (reconcile) | **nenhum** | linha | nenhuma | claim | **V5** | 4.2 |
| **Organization preview** | App | **Worker** | não | **memória** (5 entradas) | artefato durável | lock | claim+lock | V6 | 4.1D → Fase 5 |
| **Similarity foto** | App (2 caminhos) | **Worker** | **sim** (runner × requisição) | **memória** | `similarity_grouping` (M3) | `AtomicBoolean` | claim | **V4, V8, V9** | 4.1D → Fase 5 |
| **Similarity vídeo** | App (2 caminhos) | **Worker** | **sim** | **memória** | idem | `synchronized` | claim | **V4, V8, V9** | 4.1D → Fase 5 |
| **pHash backlog** | App | **Worker** | não | `fingerprint_job_run` + memória | linha + tabela existente | `AtomicBoolean` | claim | — | Fase 5 |
| **Fingerprint backlog** | App | **Worker** | não | idem | idem | `AtomicBoolean` | claim | — | Fase 5 |
| **Metadata rebuild** | App | **Worker** | não | **memória** | linha | `AtomicBoolean` | claim | — | Fase 5 |
| **Location rebuild** | App | **Worker** | não | **memória** | linha | `AtomicBoolean` | claim | — | Fase 5 |
| **Geo dataset** | App | **Worker** | não | **memória** | linha | `AtomicBoolean` | claim | — | Fase 5 |
| **Library switch** | App | **App + comando** | não | nenhum | linha para a parte de efeito | lock | lock (App) + claim (efeito) | dívida de decomposição | 4.1D |

## VIII.61 (L) Ordem de convergência

**Baseline obrigatório antes da Fase 5**, conforme já decidido: V1, V11, V8/V9 antes da similaridade,
protocolo de wake-up/espera fechado, modelo durável da similaridade fechado.

### As três decomposições possíveis

**A — Fase 4.2 de convergência da fronteira, antes da Fase 5.**
Converge Explorer, restore unitário, cleanup, catalog purge e reconcile reativo; entrega o protocolo
de wake-up/espera. A Fase 5 começa com a fronteira limpa e sete workloads homogêneos.

**B — convergência capability por capability dentro da Fase 5.**
Cada slice da Fase 5 leva junto uma capability antiga. Menos fases, mas a Fase 5 passa a ter dois
objetivos por slice, e o critério "não terminar uma fase com dois motores" fica dependendo de todos
os slices saírem.

**C — protocolo primeiro, convergência depois, Fase 5 por último.**
4.1D fecha modelo durável (similaridade, preview, library switch); 4.2 entrega o protocolo
(wake-up + espera) e converge o que depende dele; a Fase 5 fica só com os workloads novos.

**Recomendação: C.** Motivo arquitetural, não de custo: **o protocolo é pré-requisito de tudo que
muda de executor**, e converger o Explorer antes dele produziria um estado intermediário em que
operações interativas respondem "em andamento" por 5 segundos — arquitetura correta com UX degradada,
que é precisamente o tipo de estado transitório que se paga duas vezes. A ordem C garante que cada
capability migre **uma vez só**, já com o comportamento final.

### Sequência proposta

| Etapa | Conteúdo | Critério de pronto |
| --- | --- | --- |
| **4.1D** | modelo durável: `similarity_grouping` (M3), plano do preview, decomposição do library switch | modelos aprovados, sem código |
| **4.2.1** | wake-up + espera limitada + batimento do Worker | uma operação existente responde em <300 ms pela fila |
| **4.2.2** | correções: V1, V11, V8, V9, V10, V12 | defeitos fechados, sem migração |
| **4.2.3** | Explorer (quatro mutações) + restore unitário + cleanup + catalog purge | **nenhuma capability com dois motores** |
| **Fase 5** | sete workloads, com similaridade já modelada | idem |

**Nenhuma etapa termina com dois motores para a mesma capability** — que é o critério que você
definiu. Em 4.2.3, cada operação sai da App e entra no Worker no mesmo passo; não há janela em que os
dois caminhos existam.

## VIII.62 (M) Red team do protocolo

Contraexemplos reais buscados no código, contra "App expressa intenção; Worker executa".

| # | Call path real | Efeito | Por que parece violar | Classificação |
| --- | --- | --- | --- | --- |
| 1 | `MediaThumbnailController` → `videoThumbnailService.get` → `Files.move` (cache) | E3 | escreve no disco dentro da requisição | **(d) leitura sem efeito de domínio** — a resposta é o artefato |
| 2 | `SettingsParameterWebController` → `librarySwitchService.switchLibrary` → `LibraryCatalogCleanupService` → `Files.deleteIfExists` | E9+E3 | apaga arquivos e limpa catálogo na App | **(e) exceção verdadeira, parcial** — a parte de efeito é dívida de decomposição |
| 3 | `InventoryWatchService` → `reconcileAndApply` → mutação de catálogo | E2 | executa domínio na App | **(a) violação — V11** |
| 4 | `CatalogFilePurgeScheduler` → `deleteMissingBefore` | E2 | scheduler executa | **(a) violação — V5** |
| 5 | `ConversionWorkspaceCleaner` (Worker) → `Files.delete` de temporários | E10 | apaga arquivos sem execução | **(c) infraestrutura subordinada** |
| 6 | `EmbeddedDatabaseBootstrap` → instala e sobe cluster | E10 | efeito grande sem execução | **(c)** |
| 7 | `DefaultUserInitializer` (`ApplicationRunner`) → cria usuário | E4 | escreve no banco no arranque | **(b) lifecycle legítimo** |
| 8 | `AppSettingService` (`ApplicationRunner`) → semeia settings | E4 | idem | **(b)** |
| 9 | `PhashBacklogStartup` → `markRunningAsFailed` + inicia backlog | E2 | **recupera e dispara trabalho no arranque da App** | **(a) violação latente** — a recuperação é legítima (é a App recuperando), mas disparar o backlog é executar domínio |
| 10 | `RestoreNotice` / `BackgroundWorkGate` (`@EventListener`) | E5 | pausa trabalho de fundo | **(b)** — controle, não execução |
| 11 | `ExplorerDeletionService.removeEmptyTree` → `deleteEmptyTree` | E1 | apaga pastas na App | **(a) violação — V2** |
| 12 | `OrganizationAsyncRunner` → `organizationExecutor.execute(dryRun)` | E6b | executa planejamento na App | **(a) violação por reclassificação** — E6b é do motor |

**Nenhum invalida B′.** Sete são violações a converger (3, 4, 9, 11, 12 e as duas metades de 2), três
são infraestrutura ou lifecycle (5, 6, 7, 8, 10), uma é leitura (1).

**Achado novo (#9):** `PhashBacklogStartup`, sob `@Profile(APP)`, faz duas coisas no
`ApplicationReadyEvent`: marca corridas interrompidas como falhas — legítimo, é recuperação de estado
pela App — **e chama `backlogRunner.run()`**, que executa o backlog de fingerprints na App. O mesmo em
`VideoFingerprintBacklogStartup`. Registrado como **V13**: gatilho de startup que executa domínio.
Severidade média: é a Fase 5 que vai migrar essa capability, mas o padrão "startup da App executa
trabalho" precisa constar da lista.

## VIII.63 Novos achados do 4.1C

| # | Achado | Evidência | Severidade |
| --- | --- | --- | --- |
| **V13** | `PhashBacklogStartup` e `VideoFingerprintBacklogStartup` **executam** o backlog no `ApplicationReadyEvent` da App, não apenas recuperam estado | `resumeOnStartup()` chama `backlogRunner.start()` + `run()` | média |
| **V14** | A saúde do Worker existe apenas em `WorkerSupervisor.isRunning()`, **em memória e sem nenhum consumidor** (`grep` não encontra leitor). A App não tem como dizer ao usuário que o processamento está parado | VIII.58 | média — bloqueia a UX de B′ |

Somam-se a V1–V12. **Nenhum corrigido neste slice.**

## VIII.64 Decisões abertas ao fim do 4.1C

**Fechadas por este slice** (não precisam de decisão): protocolo de cinco passos; wake-up por
`NOTIFY` com polling como rede de segurança; espera limitada assíncrona de 1–3 s; sem sinal
Worker → App por ora; cinco camadas de resultado com critérios; M3 para similaridade; preview ao
Worker com plano durável; conflito serializável nas cinco mutações do Explorer; combined sem atalho;
proposta de enforcement com anotação de arquitetura.

**Precisam de decisão sua:**

| # | Pergunta | Impacto |
| --- | --- | --- |
| 1 | **Sequência C** (4.1D modelo → 4.2 protocolo+convergência → Fase 5)? | define o próximo slice |
| 2 | **Orçamento de espera**: 1 s, 2 s ou 3 s como padrão? | UX; pode ser configurável |
| 3 | **Batimento do Worker** (V14) entra em 4.2.1 como parte do protocolo? | sem ele, a UX de "processamento parado" não existe |
| 4 | **`@LibraryMutation`** (anotação de arquitetura) é aceitável como contrato explícito, ou prefere enforcement por convenção de pacote? | define o formato do gate |
| 5 | **Reter quantos agrupamentos de similaridade** por tipo — só o último limiar usado, ou os N últimos? | modelagem de 4.1D |
| 6 | **O plano do preview** vira tabela de itens ou artefato em workspace referenciado pela linha? | depende do benchmark de VIII.55 |

**Dependem de medição:** o benchmark de heap/tempo do plano (VIII.55) e o volume real de grupos de
similaridade numa biblioteca grande.

---

# Fase 4.1D — Durable Models & Lifecycle Boundaries

Fecha os três modelos que precisam existir antes da 4.2 e da Fase 5: resultado durável da
similaridade, persistência do preview e decomposição da troca de biblioteca. Mais a política geral de
retenção. Continua sendo análise: nada implementado, nenhum defeito corrigido.

## VIII.65 Similaridade — autópsia do fluxo real

| Aspecto | Foto | Vídeo |
| --- | --- | --- |
| Entry point de tela | `DuplicatesWebController` → `PhotoSimilarityAsyncRunner` | idem, `VideoSimilarityAsyncRunner` |
| Entry point de API | `DuplicateController.similarPhotos` → `groups()` **computa no miss** | `similarVideos` → idem |
| Runner | `SimilarityGroupingRunner` (compartilhado), `AtomicBoolean running` | idem |
| Cache | `SimilarityGroupCache<SimilarPhotoGroupResponse>`, `Map<Integer, CachedGroups<T>>` chaveado por **limiar** | idem, com `SimilarVideoGroupResponse` |
| Assinatura | `COUNT(fingerprints) + MAX(catalogFileId) + MAX(computedAt)` de `(PHOTO_PHASH, FFMPEG_LANCZOS_PHASH_256_V1)` | idem, com `algorithm.kind()`/`algorithm.algorithm()` injetados |
| Teto | `MAX_CANDIDATES = 8000` | `rowCap()` sobre frames |
| Pré-filtro | distância de pHash ≤ `MAX_PHASH_CANDIDATE_DISTANCE` (96) | *bucket* de duração disjunto |
| Score | SSIM sobre luminância, memoizado por par | SSIM multi-frame, memoizado por par |
| Paginação | `subList` sobre a lista em memória, teto de página vindo de `AppSetting` | idem |
| Resposta | `SimilarPhotoGroupResponse(groupId, files, similarityPercent, wastedSize, keep, deleteCandidates, reviewCandidates)` | análogo |
| Exclusões | `duplicateExclusionService` filtra candidatos **antes** do agrupamento | idem |

**Diferenças reais entre foto e vídeo:** o pré-filtro (hash × duração), a origem dos dados
(`findFingerprintedPhotos` × `findFingerprintedVideoFrames` com remontagem), e o fato de o vídeo ter
o algoritmo **injetado** (`VideoSimilarityAlgorithm`) enquanto a foto o tem **fixo no código**. A
estrutura do resultado é a mesma: grupos com um "manter" e listas de "apagar"/"revisar".

**O que a autópsia confirma sobre M3:** o conceito de validade **já existe e já é do domínio** — a
assinatura é uma pergunta ao banco, não um estado do processo. M3 não inventa nada: dá casa durável a
uma semântica que já está pronta. **M3 confirmado.**

**O que a autópsia corrige em M3:** a chave de validade proposta em 4.1C (`tipo + limiar +
assinatura`) é **insuficiente**. Ver VIII.66.

## VIII.66 Chave de validade: o que a assinatura atual não captura

A assinatura vigente responde "o conjunto de fingerprints mudou?". Ela **não** responde "o jeito de
agrupar mudou?". Três parâmetros ficam de fora, e todos são constantes de código:

| Parâmetro | Onde vive | Se mudar numa versão nova |
| --- | --- | --- |
| `MAX_PHASH_CANDIDATE_DISTANCE = 96` | `PhotoSimilarityService` | grupos diferentes, **assinatura idêntica** |
| `MAX_CANDIDATES = 8000` | idem | conjunto truncado diferente, assinatura idêntica |
| bucket de duração e quorum de frames | `VideoSimilarityAlgorithm` | idem |
| identificação do algoritmo | parâmetro da consulta, **não** do valor | dois algoritmos podem produzir a mesma tripla |

Sem correção, um resultado persistido por uma versão antiga do Nimbus seria servido por uma versão
nova com regras diferentes — silenciosamente, e sem limpeza manual não há como detectar.

**Chave de validade recomendada** — cinco componentes, todos deriváveis sem memória local:

```
grouping_key = ( media_type , algorithm_id , grouping_version , parameters_digest , fingerprint_signature )
```

| Componente | O que é | Como muda |
| --- | --- | --- |
| `media_type` | PHOTO ou VIDEO | fixo |
| `algorithm_id` | o identificador que já existe (`FFMPEG_LANCZOS_PHASH_256_V1`) | ao trocar de algoritmo |
| `grouping_version` | **constante de código**, incrementada quando a lógica de agrupamento muda | por release |
| `parameters_digest` | resumo dos parâmetros efetivos: limiar pedido, distância máxima, teto de candidatos, e os do vídeo | quando um parâmetro muda |
| `fingerprint_signature` | a tripla atual (count, maxId, maxComputedAt) | quando o conjunto muda |

**Respostas às perguntas de invalidação:**

| Pergunta | Resposta |
| --- | --- |
| A assinatura representa o quê? | o conjunto de fingerprints ativos de um `(kind, algorithm)` — não a forma de agrupar |
| Mudança de fingerprint invalida tudo? | **sim, e é intencional**: o `computedAt` máximo muda |
| Inclusão/remoção de arquivo invalida? | sim: `count` e `maxId` capturam |
| Alteração de limiar invalida? | **não invalida — é outra chave**: agrupamentos de limiares diferentes coexistem |
| Alteração do algoritmo invalida? | **hoje só por acidente**; com `algorithm_id` na chave, explicitamente |
| Nova versão do pHash invalida? | idem |
| Quorum/trimmed mean entram na identidade? | **sim**, via `parameters_digest` |
| Foto e vídeo têm assinaturas distintas? | sim, e continuam tendo: `media_type` + `algorithm_id` |
| Resultado parcial pode ser válido? | **não** — ver a publicação atômica em VIII.69 |

**Detecção de incompatibilidade sem limpeza manual:** ao consultar, o sistema calcula a chave
corrente e procura um agrupamento com **exatamente** aquela chave. Um agrupamento de versão antiga
simplesmente **não é encontrado** — não precisa ser reconhecido como inválido, ele deixa de casar. A
limpeza vira retenção, não correção.

## VIII.67 Modelo relacional da similaridade

Três tabelas. Nomes e campos propostos; tipos conforme o padrão do projeto.

### `similarity_grouping` — o artefato e sua validade

| Campo | Tipo | Papel |
| --- | --- | --- |
| `id` | bigserial PK | identidade técnica |
| `public_id` | uuid | referência externa, como nas demais |
| `media_type` | varchar(10) | PHOTO / VIDEO |
| `algorithm_id` | varchar(60) | o identificador do algoritmo |
| `grouping_version` | integer | versão da lógica de agrupamento |
| `parameters_digest` | varchar(64) | resumo dos parâmetros efetivos |
| `min_similarity` | integer | limiar pedido (também está no digest; fica explícito para consulta) |
| `fingerprint_signature` | varchar(120) | a tripla atual |
| `status` | varchar(20) | `BUILDING` / `ACTIVE` / `SUPERSEDED` |
| `execution_id` | bigint FK → `execution` | quem produziu (**SET NULL** ao apagar a execução) |
| `group_count`, `member_count` | integer | totais, para a UI não precisar contar |
| `computed_at`, `published_at` | timestamp | quando terminou e quando virou `ACTIVE` |

**Chave lógica:** `(media_type, algorithm_id, grouping_version, parameters_digest, fingerprint_signature)`.
**Índice único parcial** sobre essa tupla `WHERE status = 'ACTIVE'` — garante **um** agrupamento
corrente por chave, deixando `BUILDING` e `SUPERSEDED` coexistirem.

### `similarity_group` — um grupo

| Campo | Papel |
| --- | --- |
| `id` bigserial PK | |
| `grouping_id` FK → `similarity_grouping` **ON DELETE CASCADE** | dono |
| `ordinal` integer | ordem de apresentação, gravada na produção |
| `similarity_percent` integer | representativo do grupo |
| `member_count` integer, `wasted_bytes` bigint | o que a lista mostra sem abrir o grupo |
| `keep_catalog_file_id` bigint | o "manter" escolhido pela política |

**Índice:** `(grouping_id, ordinal)` — é exatamente a consulta da listagem paginada.

### `similarity_group_member` — a participação

| Campo | Papel |
| --- | --- |
| `group_id` FK → `similarity_group` **ON DELETE CASCADE** | |
| `catalog_file_id` bigint | o arquivo |
| `role` varchar(10) | `KEEP` / `DELETE` / `REVIEW` |
| `score` integer | similaridade **contra o membro `KEEP`** |
| `ordinal` integer | ordem dentro do grupo |

**Índices:** `(group_id, ordinal)` para abrir o grupo; `(catalog_file_id)` para a invalidação por
arquivo removido (é o que `SimilarityCaches.evictAll` faz hoje em memória).

### Decisões de modelagem justificadas

- **`group id` não precisa ser estável entre execuções.** O `groupId` de hoje é derivado do conteúdo
  do grupo, e nenhuma tela guarda referência a ele entre recálculos. Estabilidade custaria casar
  grupos entre agrupamentos — trabalho sem consumidor.
- **`score` guarda a similaridade contra o `KEEP`**, não a matriz de pares. A matriz é O(n²) por
  grupo e a UI mostra "este é X% parecido com o que você vai manter". Guardar a matriz seria
  persistir um detalhe do algoritmo, não o resultado.
- **Limiar aparece duas vezes** (explícito e no digest) de propósito: o digest garante a identidade,
  a coluna permite "quais limiares já calculei?" sem decodificar nada.
- **Sem tabela genérica de JSON.** Cada campo acima é consultado, ordenado ou paginado.

## VIII.68 Concorrência e deduplicação da similaridade

Semântica futura, expressa nos mecanismos que já existem — e que tornam `AtomicBoolean` desnecessário.

| Pergunta | Resposta | Mecanismo |
| --- | --- | --- |
| Dois agrupamentos PHOTO simultâneos? | **não** | `concurrencyLimit() = 1` no handler |
| Limiares diferentes coexistem? | **sim, como resultados**; não como cálculos simultâneos | chave de validade distinta; o limite serializa os cálculos |
| PHOTO e VIDEO simultâneos? | **decisão de recurso**: podem, se forem tipos de execução distintos | dois `ExecutionType` ou um com limite 1 |
| Mesma chave já em cálculo | **deduplica**: devolve a execução existente | `dedup_key` = a própria chave de validade |
| Nova solicitação equivalente enquanto uma roda | **retorna a existente**, como o inventário já faz | `ExecutionEnqueueService` já tem esse comportamento |
| Resultado antigo serve durante o recálculo? | **sim** | o `ACTIVE` anterior permanece até a publicação |
| Quando o novo vira "current"? | na publicação atômica | VIII.69 |

**`dedup_key` da similaridade** = a chave de validade serializada. Consequência direta: dois pedidos
idênticos viram um; pedidos de limiares diferentes são trabalhos diferentes; e um pedido após
mudança de fingerprint é outro trabalho, porque a assinatura mudou.

**Lock de caminho:** não se aplica — a similaridade não toca no filesystem. A exclusão vem do claim e
do `concurrencyLimit`, que é o ponto: **o `AtomicBoolean` desaparece sem substituto**, porque a
função dele passa a ser exercida por um mecanismo que já é cross-process.

## VIII.69 `Execution` × `SimilarityGrouping`: publicação segura

Separação: a **`Execution` é o lifecycle do cálculo** (quem pediu, quando começou, progresso, estado
terminal); o **`SimilarityGrouping` é o resultado de domínio**, reutilizável enquanto válido.

**Desenho de publicação — confrontado com o domínio e adotado:**

```
1. handler cria grouping em BUILDING            (chave de validade preenchida)
2. grava grupos e membros                       (em lote, sem transação gigante)
3. valida completude                            (group_count e member_count conferem)
4. numa transação:  ACTIVE anterior → SUPERSEDED ; este BUILDING → ACTIVE
5. execução termina FINISHED
```

Por que funciona aqui: **a consulta só enxerga `ACTIVE`**, então nada parcial é visível; e o índice
único parcial garante que o passo 4 não pode produzir dois correntes — se dois cálculos equivalentes
escapassem da deduplicação, o segundo falharia no índice, o que é o comportamento certo.

| Evento | Consequência |
| --- | --- |
| Falha no meio | fica um `BUILDING` órfão; a próxima execução da mesma chave o remove antes de começar, e a limpeza periódica pega o resto |
| Cancelamento | idem: nada publicado, o `ACTIVE` anterior continua servindo |
| Reclaim (worker morto) | idem — e como o resultado é reproduzível, não há compensação a fazer |
| Retry | **grava em novo `BUILDING`**, nunca reescreve o anterior. Reescrever exigiria limpar antes, e limpar é justamente o que não se pode fazer com algo que talvez esteja publicado |
| Apagar a `Execution` por retenção | o agrupamento **sobrevive** (`execution_id` vira nulo): ele é do domínio |

## VIII.70 Consultas e paginação

Sem desserializar nada. As consultas que a UI precisa, todas indexadas:

| Necessidade | Consulta |
| --- | --- |
| Existe resultado válido? | `SELECT id, group_count, member_count FROM similarity_grouping WHERE <chave> AND status='ACTIVE'` |
| Listar grupos, paginado | `WHERE grouping_id = ? ORDER BY ordinal LIMIT ? OFFSET ?` |
| Total de grupos | coluna `group_count` — sem `COUNT(*)` |
| Ordenar por relevância/tamanho | `ordinal` é gravado já ordenado pela política de apresentação; ordenações alternativas viram índice adicional se surgirem |
| Abrir um grupo | membros `WHERE group_id = ? ORDER BY ordinal` |
| "Calculando" | há execução ativa do tipo com aquele `dedup_key` |
| "Ainda não calculado" | nenhum `ACTIVE` para a chave e nenhuma execução ativa |
| Resultado stale | não existe: uma chave diferente simplesmente não casa |
| Válido enquanto outro calcula | o `ACTIVE` responde; a tela pode indicar que há recálculo em curso |

**A mudança de contrato que isso impõe:** a consulta **nunca computa**. Hoje `groups()` computa no
miss; no modelo novo ela responde "ainda não calculado" e oferece enfileirar. É o que elimina V4, e
é requisito, não estética.

## VIII.71 Retenção da similaridade

**Critério arquitetural** (o que é correto): reter **o `ACTIVE` de cada chave de validade que ainda
possa ser pedida**, e nada mais. Isso decompõe em três regras:

| Regra | Por quê |
| --- | --- |
| R-A | Ao publicar, o `ACTIVE` anterior **da mesma chave** vira `SUPERSEDED` e é apagado — ele nunca mais será pedido, porque a chave dele não se repete |
| R-B | Agrupamentos cuja `fingerprint_signature` não é mais a corrente são inalcançáveis: apagáveis a qualquer momento |
| R-C | Agrupamentos `BUILDING` mais velhos que um limite são resíduo de falha: apagáveis |

Sobram apenas os `ACTIVE` da assinatura corrente, um por limiar pedido. **O crescimento é limitado
pelo número de limiares distintos que o usuário experimenta entre duas mudanças de fingerprint** — e
a cada nova foto fingerprintada, todos viram inalcançáveis de uma vez.

Isso responde à pergunta sem N arbitrário: **não é "quantos manter", é "o que ainda é alcançável"**.
Políticas por TTL, LRU ou "últimos N" seriam aproximações piores de uma resposta exata que o domínio
já dá.

**Tuning que sobra, e depende de medição:**

| Parâmetro | Pergunta | Medição necessária |
| --- | --- | --- |
| Teto de limiares por assinatura | vale limitar quantos limiares coexistem? | quantos limiares distintos um usuário real usa numa sessão |
| Prazo do `BUILDING` órfão | quanto tempo antes de considerar resíduo | duração típica de um agrupamento completo |
| Apagar `SUPERSEDED` na hora ou em varredura | latência × transação longa | tamanho médio de um agrupamento |

**Estimativa de volume — o que dá para dizer honestamente com o repositório:** o teto de candidatos é
8.000 fotos; grupos e membros dependem inteiramente de quantas fotos são parecidas, o que é
propriedade da coleção e **não está no repositório**. Uma linha de `similarity_group_member` tem
ordem de dezenas de bytes; mesmo um cenário improvável de 8.000 membros distribuídos em 2.000 grupos
dá centenas de kilobytes por agrupamento. **O volume não é o risco; o número de chaves coexistentes
é** — e R-A/R-B já o limitam.

**Benchmark necessário para fechar o tuning:** sobre uma biblioteca real com fingerprints calculados,
medir número de grupos e de membros por limiar (70, 80, 90, 95), o tempo de gravação do agrupamento
completo e o tempo da consulta paginada. Não exige código novo além do próprio modelo.
## VIII.72 Organization Preview — o que existe

| Aspecto | Evidência |
| --- | --- |
| Estrutura | `OrganizationPlan(sourcePath, targetPath, layout, execute, summary, items)` |
| Item | `OrganizationItem` com **21 componentes**: ids, nome, dois caminhos, ano-mês, dia, categoria, subcategoria, tipo, regra, motivo, tamanho, e **seis flags** (samePath, missingDate, targetExists, duplicateTarget, conflict, conflictType) mais localização e confiança |
| Volume declarado | "até centenas de milhares de itens" (Javadoc do `OrganizationPlanStore`) |
| Onde vive | `OrganizationPlanStore`: `LinkedHashMap` de acesso, **teto de 5**, chaveado por `executionId` |
| Quem escreve | `OrganizationExecutor` (`put`), no dry-run **e** no run real |
| Quem lê | `OrganizationService.plan(executionId)` — a tela de resultado |
| Paginação | feita **na tela**, sobre a lista inteira em memória |
| Relação com execute | **nenhuma**: `execute` chama `organizationPlanner.preview` de novo |

**Consequência de escala, que nenhuma das análises anteriores tinha quantificado:** 21 campos por
item, com quatro strings de caminho/nome, dá ordem de **algumas centenas de bytes por item** apenas
em objetos Java. Um plano de 200 mil itens é dezenas de megabytes de heap — e o `Map` guarda **cinco**.
No pior caso, o preview sozinho pode ocupar mais heap que o orçamento inteiro da App (`-Xmx1g` no
instalado).

## VIII.73 Preview — alternativas de persistência

| # | Modelo | A favor | Contra |
| --- | --- | --- | --- |
| **A** | tabela `organization_plan` + `organization_plan_item` | consultável, paginável por SQL, `CASCADE` resolve limpeza, atômico | escrita de 200 mil linhas por preview; o plano vira "dado" sem ser domínio |
| **B** | artefato no workspace (JSON/NDJSON) referenciado pela linha | escrita sequencial barata, sem pressão no banco, leitura por faixa | precisa de limpeza própria, paginação exige índice ou varredura, segurança de caminho |
| **C** | híbrido: **resumo + amostra** na base, artefato completo no workspace | a tela mostra sumário e primeiras páginas sem tocar em arquivo | duas fontes para a mesma coisa |
| **D** | **não persistir; recalcular sob demanda paginada** | zero armazenamento | recalcular um plano de 200 mil itens a cada página é pior que tudo |

**Recomendação: A, com uma condição de escala a ser medida.**

Razões arquiteturais, na ordem:

1. **O plano é consultado por página, filtrado e ordenado.** Isso é consulta, e consulta é o que o
   banco faz. Em B, paginar exige reimplementar índice sobre arquivo.
2. **Atomicidade e limpeza saem de graça.** `ON DELETE CASCADE` com a `Execution` resolve retenção,
   crash e restart sem código novo. Em B, um arquivo órfão é um problema novo, com as mesmas
   perguntas que o workspace de conversão já obrigou a responder.
3. **Não há caminho de usuário no artefato.** Em B, o artefato guarda caminhos absolutos num arquivo
   do workspace — mais uma superfície a validar.

**A condição:** se o benchmark (VIII.74) mostrar que gravar o plano no banco domina o tempo do
preview — por exemplo, gravar 200 mil linhas custando mais que produzir o plano —, a escolha passa
para **C**: sumário e primeiras N páginas na base, artefato completo em NDJSON no workspace, com a
linha apontando para ele. **C só se justifica por medição**, e é por isso que o benchmark existe.

**O que não muda com a medição:** o preview vai ao Worker (é E6b), o plano tem casa durável, e a tela
lê da base.

## VIII.74 Benchmark do preview — metodologia

Reprodutível, e executável no início da 4.2 ou num micro-slice próprio.

**Cenários:** pequeno (~1 000 itens), médio (~50 000), extremo (~250 000, acima do maior caso real
esperado). Gerados por um fixture que cria entradas de catálogo sintéticas — não precisa de arquivos
no disco, porque o planejamento lê o catálogo.

**Medidas, por cenário:**

| # | O que medir | Como |
| --- | --- | --- |
| 1 | tempo de produção do plano | tempo de `organizationPlanner.preview` |
| 2 | heap retido pelo plano | tamanho retido do `OrganizationPlan` (heap dump ou instrumentação) |
| 3 | heap retido pelo `Map` cheio | o mesmo × 5 |
| 4 | tamanho serializado | JSON e NDJSON do plano |
| 5 | tempo de serialização | idem |
| 6 | tempo de gravação em banco | `INSERT` em lote dos itens |
| 7 | tempo de gravação em arquivo | escrita sequencial de NDJSON |
| 8 | tempo da primeira página | `SELECT … LIMIT 50` × leitura do arquivo |
| 9 | tempo de uma página no meio | `OFFSET 100000` × seek no arquivo |

**Critério de decisão, fixado antes de medir:** se (6) for maior que (1) no cenário extremo, adotar
**C**; caso contrário, **A**. Se (2) no cenário médio já se aproximar do heap da App, isso é evidência
adicional e independente de que o preview precisa sair do processo da App — reforçando o que a
classificação E6b já determinou.

## VIII.75 Validade e staleness do preview

A frase proposta — *"preview mostra o que seria feito com o estado observado naquele momento; execute
recalcula sob o estado atual"* — está **correta e deve ser adotada**, com dois refinamentos:

| Pergunta | Resposta |
| --- | --- |
| É snapshot informativo? | **sim**, e é evidência para decidir, não entrada de execução |
| Pode ficar stale? | **sim, sempre** — inclusive entre a produção e a primeira página |
| Por quanto tempo vale? | não há prazo correto; o que existe é **detectabilidade** |
| A UI deve avisar? | **sim**: o plano é do instante em que foi produzido, e a execução recalcula |
| Precisa de assinatura do estado da biblioteca? | **sim, para detectar**, não para bloquear |
| Precisa de geração de inventário/revisão de catálogo? | é a forma natural da assinatura |
| Deve haver "preview expirado"? | **não como bloqueio**. Como *indicação*: "o catálogo mudou desde este plano" |
| `execute` pode ocorrer com preview velho? | **sim** — ele recalcula; é o que torna o velho inofensivo |
| Ligar preview e execute? | **sim, só para auditoria/UX**: `execute` pode registrar `preview_execution_id` |
| Isso acopla? | **não**, se for apenas referência informativa — nunca condição de execução |

**Assinatura do plano:** a mesma ideia da similaridade, aplicada ao catálogo da pasta de origem —
contagem e maior `updatedAt` das entradas sob a origem. Comparar a assinatura gravada com a corrente
responde "mudou desde então?" sem recalcular nada. **É indicação para a tela, jamais guarda de
execução.**

## VIII.76 Retenção do preview

O plano é **resultado operacional descartável**, não domínio: apagá-lo não tira nenhuma capacidade do
produto — só a evidência daquela decisão.

| Regra | Definição |
| --- | --- |
| Dono | a `Execution` que o produziu |
| Apagamento | `ON DELETE CASCADE` com a execução |
| TTL próprio | **não** — herda a retenção de execuções, que já existe |
| Por quantidade | **não** como requisito. O teto de 5 de hoje é consequência de ser heap, não uma regra de produto |
| Plano parcialmente gravado | possível se a execução morrer durante a escrita; a execução fica `INTERRUPTED` e o plano incompleto é inútil por definição — apagado com ela |
| Crash durante geração | idem |
| Restart | nada a fazer: o plano ou está completo, ou pertence a uma execução não terminada |
| Artefato órfão (se **C**) | precisa de varredura por `executionId` inexistente — o mesmo padrão do `ConversionWorkspaceCleaner` |

A última linha é um argumento adicional a favor de **A**: em **C**, o preview herda uma classe de
problema que hoje só a conversão tem.

## VIII.77 Library Switch — o fluxo real

Rastreado em `LibrarySwitchService.switchLibrary`, que é `@Async` na App:

```
1. openMaintenanceWindow()            advisory lock persistente  ← já é durável
2. inventoryWatchService.pause()      memória local da App
3. waitForCancellation()              requestAllCancellations() + poll de active(), com deadline
4. cleanupService.clear(oldFolder)    apaga catálogo E cache de thumbnails      ← EFEITO E2+E3
5. appSettingService.update(WATCH_FOLDER)                                       ← configuração E4
6. inventoryWatchService.reconfigureAndInventory()                              ← reconfigura + enfileira
```

**Correção importante de premissa.** A instrução deste slice supunha que "`BackgroundWorkGate` atual é
memória local e não coordena o Worker". A varredura mostra outra coisa:

- `BackgroundWorkGate` (volatile boolean) **não é o portão de trabalho**: ele classifica log durante
  shutdown/restore. Não participa de exclusão.
- O portão real é `OperationLockService.openMaintenanceWindow()`, um **advisory lock do PostgreSQL**,
  e o `ExecutionDispatcher` consulta `backgroundWorkPaused()` como **primeiro passo** de cada
  `dispatchOne()`. Isso **já coordena o Worker cross-process**, e já morre sozinho se o processo que
  o segura cair.

Ou seja: **o quiesce já existe e já é durável.** O que falta não é o mecanismo — é o que segue.

## VIII.78 Modo de manutenção: o que já atende e o que falta

| Requisito | Estado | Evidência |
| --- | --- | --- |
| App impede novos claims | **atendido** | `backgroundWorkPaused()` é o passo 1 do dispatcher |
| `PENDING` existentes não somem | **atendido** | ninguém os toca |
| `RUNNING` recebem cancelamento | **atendido** | `requestAllCancellations()` escreve na linha |
| Worker para em ponto seguro | **atendido** | cancelamento cooperativo nos handlers |
| App sabe quando chegou a zero | **atendido** | `executionQueryService.active()` |
| Crash da App não bloqueia para sempre | **atendido** | o advisory lock morre com a sessão |
| Restart recupera | **atendido** | não há estado a recuperar |
| Não depende de volatile | **atendido** | é lock de banco |
| Combined mantém semântica | **atendido** | é o mesmo lock |
| **Distinguir "parado por manutenção" de "sem executor"** | **falta** | nada expõe o motivo (V14) |
| **`inventoryWatchService.pause()` é memória local** | **falta** | num Worker remoto, o watcher da App é o único; hoje funciona por acidente de topologia |

**Recomendação: não criar tabela de `maintenance_mode`.** Seria substituir um mecanismo que já tem
todas as propriedades exigidas — inclusive a mais difícil, morrer sozinho — por outro que precisaria
reimplementá-las. O que falta é **visibilidade**, e visibilidade é o modelo de saúde de VIII.81.

## VIII.79 Library Switch — decomposição

Classificando cada passo pela fronteira B′:

| Passo | Categoria | Dono | Observação |
| --- | --- | --- | --- |
| Validar a nova pasta | leitura | **App** | validação prévia |
| Abrir a janela de manutenção | E5 | **App** | controle do motor |
| Pausar o watcher | E5 | **App** | lifecycle |
| Pedir cancelamento e aguardar zero | E5 | **App** | controle |
| **Limpar catálogo da biblioteca antiga** | **E2** | **Worker** | é mutação de catálogo em massa |
| **Limpar cache de thumbnails** | **E3** | Worker (junto) ou App | derivado; acompanha o passo acima por coesão |
| Trocar `WATCH_FOLDER` | E4 | **App** | configuração |
| Reconfigurar watcher e inventariar | E5 + enfileirar | **App** | lifecycle + produção de intenção |
| Fechar a janela | E5 | **App** | controle |

**Ordem correta**, derivada das invariantes e não do código atual:

```
APP    1. valida a nova pasta
APP    2. abre a janela de manutenção          (novos claims param)
APP    3. pausa o watcher
APP    4. cancela e aguarda zero RUNNING
APP    5. enfileira o comando de limpeza da biblioteca antiga
       ↳ mas a janela impede o claim… ← ver a inversão abaixo
```

**A inversão que o desenho revela.** O passo 5 não pode acontecer com a janela fechada: ela existe
justamente para impedir claims. Duas saídas:

| Saída | Como funciona | Avaliação |
| --- | --- | --- |
| **S1 — janela seletiva** | a manutenção bloqueia tudo **exceto** o tipo de execução da própria troca | exige que o gate saiba de tipos; é um conceito novo no dispatcher |
| **S2 — duas fases** | fase 1: quiesce, cancelar, aguardar zero, **fechar a janela**; fase 2: enfileirar a limpeza e aguardar seu término com a janela **aberta**, mas com o watcher pausado e a configuração ainda apontando para a pasta antiga | não precisa de conceito novo; a janela protege a fase de drenagem, e a limpeza é ela mesma uma execução como outra qualquer |

**Recomendo S2.** A limpeza da biblioteca antiga não precisa de exclusividade absoluta: ela precisa
que **nada esteja rodando sobre a biblioteca antiga**, o que a fase 1 garante, e que **nada novo
comece sobre ela**, o que o watcher pausado mais a configuração inalterada garantem. S1 introduziria
no dispatcher a noção de "manutenção parcial", que é precisamente o tipo de exceção que abre brecha.

## VIII.80 Library Switch — máquina de estados, commit point e recuperação

**Commit point:** a troca de `WATCH_FOLDER`. Antes dela, a biblioteca corrente ainda é a antiga e
tudo é revertível por abandono; depois, a nova é a corrente e o que falta é convergência.

**Estados:**

```
VALIDATING → QUIESCING → CLEARING → SWITCHING → RESUMING → DONE
                  ↓          ↓           ↓          ↓
                ABORTED   ABORTED     (commit)   RECOVERING
```

| Falha em | Consequência | Recuperação |
| --- | --- | --- |
| `VALIDATING` | nada aconteceu | erro na tela |
| `QUIESCING` (timeout) | nada aconteceu; hoje lança `cancelTimeout` | erro na tela, watcher volta |
| `CLEARING` (Worker falha) | catálogo antigo **parcialmente** limpo | a execução fica `INTERRUPTED`; retomável — limpar de novo é idempotente |
| **App morre após `SWITCHING` e antes de `RESUMING`** | configuração nova, **watcher parado** | ao subir, a App reconfigura o watcher a partir da configuração — **já é o comportamento**, porque o watcher lê `WATCH_FOLDER` no arranque |
| `RESUMING` | watcher não sobe | `recoverInventoryMonitoring()` já existe |

**Precisa de `Execution` própria?** A parte de limpeza, **sim** — é E2 e precisa de claim, progresso e
retomada. **Precisa de entidade `LibrarySwitchOperation`?** **Não.** A máquina de estados acima tem
exatamente um passo que sobrevive a crash com consequência (a limpeza), e esse passo já é uma
`Execution`. Os demais são ou revertíveis por abandono, ou idempotentes no arranque. Criar uma
entidade de saga seria modelar estado que ninguém precisa consultar.

**Retomada:** se a App morrer durante `CLEARING`, ao subir ela encontra a configuração **antiga**
(o commit não aconteceu) e a limpeza interrompida. O correto é **não retomar automaticamente**: a
troca é uma ação deliberada do usuário, e retomá-la sozinha seria decidir por ele. A tela deve
mostrar que houve uma troca incompleta.

## VIII.81 Backup/restore como red team, e o modelo de saúde

**Backup/restore permanece lifecycle da App** — nenhuma evidência nova contradiz. Mas ele expõe o
mesmo buraco:

| Pergunta | Resposta |
| --- | --- |
| Restore precisa de quiesce? | **sim** — e já usa: `RestoreInProgressInterceptor` + o mesmo gate |
| Como impedir claims? | a janela de manutenção, igual à troca de biblioteca |
| Processos externos? | ffmpeg pertence a uma execução; cancelar a execução encerra o processo |
| O banco fica indisponível? | **sim, durante o restore** — e é o motivo de o Worker não poder coordená-lo |
| O Worker precisa sair? | **não** — ele perde a conexão, falha, devolve o trabalho e volta quando o banco voltar |
| O batimento precisa refletir isso? | **sim**, e é o ponto: sem isso, "restore em curso" é indistinguível de "Worker quebrado" |

### Modelo mínimo de saúde do Worker

Uma tabela `worker_instance`, uma linha por processo worker:

| Campo | Papel |
| --- | --- |
| `worker_id` PK | o identificador que o `WorkerIdentity` já gera |
| `started_at`, `heartbeat_at` | vivo? |
| `state` | `STARTING` / `IDLE` / `BUSY` / `QUIESCING` / `STOPPING` |
| `stopped_reason` | `SCHEMA_INCOMPATIBLE`, `SHUTDOWN`, nulo |
| `application_version` | qual build está executando |

Com isso, e com o que já existe, a App distingue as oito situações:

| Situação | Como se deduz |
| --- | --- |
| Saudável e ocioso | batimento recente, `state=IDLE` |
| Saudável e ocupado | batimento recente, `state=BUSY` |
| Iniciando | `state=STARTING` |
| Em quiesce | `state=QUIESCING` **ou** janela de manutenção aberta |
| Parado por manutenção | **janela aberta** e sem batimento perdido — é a distinção que faltava |
| Schema incompatível | `stopped_reason=SCHEMA_INCOMPATIBLE` |
| Em loop de restart | batimentos curtos e repetidos com `started_at` sempre novo |
| Batimento expirado | sem linha atualizada dentro da janela |

**O mínimo mesmo:** `worker_id`, `heartbeat_at`, `state`, `stopped_reason`. Os outros dois campos são
úteis e baratos, mas se a régua for "o mínimo", são dispensáveis. **Nada de métricas, histórico ou
séries temporais** — a pergunta que precisa ser respondida é apenas "posso contar com um executor?".
## VIII.82 Política geral de resultados duráveis

Quatro classes, com donos e regras diferentes — deliberadamente não uniformizadas.

| Classe | Definição | Exemplos | Dono | Apagamento | Backup |
| --- | --- | --- | --- | --- | --- |
| **A. Operacional descartável** | relatório daquele run; sem ele, nenhuma capacidade se perde | `conversion_item_result`, `execution_error`, `execution_step`, **plano do preview** | a `Execution` | `ON DELETE CASCADE` | acompanha a execução; perdê-lo não é perda de dado do usuário |
| **B. Domínio reutilizável** | continua verdadeiro depois de a execução ser esquecida | `movement`, **`similarity_grouping`** | o domínio | por **alcançabilidade** (similaridade) ou por regra de negócio (`movement` vive enquanto o item estiver em quarentena) | **sim** |
| **C. Artefato temporário** | derivado, regenerável, fora do banco | temporário de conversão, cache de thumbnail, workspace | o processo que o criou | varredura de órfãos por `executionId` inexistente | **não** |
| **D. Histórico/auditoria** | o que aconteceu, para leitura humana | a própria `execution` | retenção configurada, já existente | sim |

**O teste para classificar** (o mesmo de 4.1C, agora com nome): *apagar isto tira alguma capacidade do
produto?* Sim → B. Não, mas explica um run → A. Não, e é regenerável → C.

**Consequências que já mudam decisões deste slice:**

- `similarity_grouping` é **B**: sobrevive à execução (`execution_id` vira nulo), e por isso a chave
  de validade é do domínio, não do motor.
- O plano do preview é **A**: morre com a execução, `CASCADE`, sem TTL próprio.
- Nenhuma tabela nova precisa de política de backup própria: A e D acompanham a execução, B acompanha
  o catálogo, C não entra em backup.

## VIII.83 Enforcement: refutando a anotação

A proposta de 4.1C era `@LibraryMutation`. Tentando quebrá-la:

| Objeção | Peso |
| --- | --- |
| **Uma anotação é declaração, não propriedade.** Quem escreve um método mutador novo simplesmente não a coloca, e a regra "quem chama `Files.*` sem estar marcado falha" **pega** esse caso — mas só se a regra for por *chamada*, não por *marcação* | resolvível |
| **Vira escape hatch:** anotar passa a ser o jeito de calar o gate | **sério** |
| Não distingue *qual* biblioteca: `Files.delete` num temporário do workspace e num arquivo do usuário são a mesma chamada | **sério — e nenhuma anotação resolve**, porque a diferença está no valor do caminho, em tempo de execução |
| Duas anotações (`@LibraryMutation`, `@CatalogMutation`)? | pior: duplica a decoração sem aumentar o poder |

**Comparação honesta das ferramentas:**

| Ferramenta | O que prova | Onde falha |
| --- | --- | --- |
| Anotação + ArchUnit | quem chama `Files.*` fora do conjunto marcado | não distingue destino; marcação é voluntária |
| **Port explícito** (uma interface por efeito: `LibraryFileMutations`) | **só quem tem o port muta**; o port é injetado, e ArchUnit verifica **quem pode injetá-lo** | exige refatorar as chamadas diretas para o port |
| Fronteira de pacote | tudo dentro de `..worker..` pode, fora não | os serviços de domínio são compartilhados — **é exatamente o que não funciona hoje** |
| Marker interface | igual ao port, sem a passagem obrigatória | não obriga a usar |
| Slices do ArchUnit | ciclos e camadas | não fala de efeito |
| Análise estática própria | qualquer coisa | custo de manutenção alto, e é código que ninguém revisa |

**Recomendação: port explícito, não anotação.**

O desenho: as mutações de biblioteca passam a existir **apenas** atrás de um port (`SecureFileMove`
já é quase isso; faltam os `Files.delete`/`Files.move` diretos). A regra do ArchUnit deixa de ser
"quem chama `Files.*`" e passa a ser duas, ambas estruturais e sem declaração voluntária:

1. **nenhuma classe fora do port chama `Files.move`/`Files.delete`/`Files.write`** sobre caminhos —
   com uma lista fechada de exceções para workspace e infraestrutura, que é pequena e revisável;
2. **o port só pode ser injetado em classes alcançáveis a partir de um handler** — verificável por
   grafo de dependências, que é o que o ArchUnit faz bem.

A diferença prática em relação à anotação: **esquecer de anotar passa despercebido; não poder injetar
não passa.** Um método mutador novo que não use o port precisa chamar `Files.*` diretamente — e cai na
regra 1.

**O que continua exigindo auditoria semântica** (e deve ser aceito como tal): resultado necessário à
UI existindo só em memória, e cache sem versão. Nenhuma ferramenta estática distingue cache de
verdade.

## VIII.84 Red team dos três modelos

**Similaridade persistida:**

| # | Ataque | Resultado |
| --- | --- | --- |
| 1 | `DuplicateGroupAssembler` monta a resposta a partir de objetos de domínio carregados; persistir só ids obriga a recarregar para renderizar | **não quebra** — a listagem passa a ser uma consulta com join, que é o normal. Mas confirma que a tabela precisa dos campos de apresentação (contagem, bytes desperdiçados) para não exigir join a cada linha |
| 2 | `SimilarityCaches.evictAll(movedIds)` remove membros quando arquivos vão para a quarentena — hoje, em memória | **não quebra**: vira `DELETE` por `catalog_file_id`, e o índice proposto existe para isso. **Mas** o agrupamento fica com contagens desatualizadas: `group_count`/`member_count` precisam ser recalculados ou marcados como "reduzidos" |
| 3 | Exclusões (`duplicateExclusionService`) mudam **entre** o cálculo e a consulta | **tensiona**: hoje o filtro é aplicado antes de agrupar. Se a exclusão mudar depois, o agrupamento persistido contém pares que o usuário já excluiu. **Deve entrar na chave de validade** — uma assinatura das exclusões |
| 4 | Dois limiares em cálculo simultâneo com o mesmo `dedup_key`? | não: a chave inclui o limiar, então são chaves distintas e o `concurrencyLimit` serializa |

**Achado do ataque 3 → refinamento da chave:** `parameters_digest` deve incluir uma **assinatura das
exclusões de duplicados**, pela mesma razão que inclui os parâmetros do algoritmo. Sem isso, mudar
uma exclusão não invalidaria o agrupamento.

**Preview durável:**

| # | Ataque | Resultado |
| --- | --- | --- |
| 5 | `OrganizationItem.internalCatalogFileId` é `@JsonIgnore` — o plano tem um campo que não serializa | **quebra a opção B** (artefato JSON) sem tratamento: o id interno se perderia. Em A é uma coluna comum. **Reforça A** |
| 6 | O plano é produzido pelo mesmo executor que executa (`OrganizationExecutor`), com `dryRun` decidindo | **não quebra**, mas mostra que a separação preview/execute é um `boolean`, não dois caminhos — o que facilita a migração e é bom sinal |
| 7 | `execute` grava o plano no store **também** no run real | **desperdício** hoje; com persistência, seria escrever 200 mil linhas por execução real sem consumidor. **Deve ser removido junto**: o plano só interessa no preview |

**Library switch decomposto:**

| # | Ataque | Resultado |
| --- | --- | --- |
| 8 | `inventoryWatchService.pause()` é memória local da App | **não quebra hoje** (o watcher só existe na App), mas é premissa de topologia: se um dia houver watcher fora da App, pausar deixa de bastar |
| 9 | `waitForCancellation` tem deadline e **lança** ao estourar | **tensiona**: a troca falha e o usuário vê um erro, mas a janela fecha e o sistema volta. Correto, e a decomposição preserva |
| 10 | `cleanupService.clear` apaga **catálogo e cache** juntos | ao virar comando, os dois efeitos vão juntos — coeso, e o cache é regenerável |

## VIII.85 Novos achados do 4.1D

| # | Achado | Evidência | Severidade |
| --- | --- | --- | --- |
| **V15** | A chave de validade da similaridade **não cobre as exclusões de duplicados**: alterar uma exclusão não invalida o agrupamento em cache, e a tela pode mostrar como semelhantes dois arquivos que o usuário mandou ignorar | ataque 3; `SimilarityGroupSupport.withoutExcluded` filtra antes de agrupar, e a assinatura só olha fingerprints | **média — existe hoje, em memória** |
| **V16** | A chave de validade **não cobre os parâmetros do algoritmo** (`MAX_PHASH_CANDIDATE_DISTANCE`, `MAX_CANDIDATES`, quorum do vídeo): uma versão nova do Nimbus com parâmetros diferentes reusaria silenciosamente um agrupamento produzido pela versão antiga | VIII.66 | baixa hoje (cache morre com o processo), **alta assim que persistir** |
| **V17** | `OrganizationExecutor` grava o plano no `OrganizationPlanStore` **também na execução real**, onde ninguém o lê | ataque 7 | baixa (desperdício de heap), **média se persistido** |

V15–V17 somam-se a V1–V14. **Nenhum corrigido neste slice.**

## VIII.86 Backlog da Fase 4.2

| Item | Classificação | Depende de |
| --- | --- | --- |
| **V1** — `markInterruptedExecutions` encerra execução viva | **BLOCKER antes de qualquer migração interativa** | — |
| **V11** — reconcile reativo executa na App | **BLOCKER antes de qualquer migração** | — |
| Wake-up (`LISTEN/NOTIFY` + polling adaptativo) | **BLOCKER antes de qualquer migração interativa** | — |
| Bounded wait na App | **BLOCKER antes de qualquer migração interativa** | wake-up |
| **Worker heartbeat** (V14) | **BLOCKER antes de qualquer migração interativa** | — (sem ele a UX de "sem executor" não existe) |
| **V8/V9** — exclusão por `AtomicBoolean` | **BLOCKER antes da Similarity** | modelo de similaridade |
| **V15/V16** — chave de validade incompleta | **BLOCKER antes da Similarity** | modelo de similaridade |
| Modelo `similarity_grouping` (M3) | **BLOCKER antes da Similarity** | benchmark de volume |
| **V4** — cálculo síncrono na API REST | **BLOCKER antes da Similarity** | modelo |
| Modelo do plano de preview | **BLOCKER antes do Preview** | benchmark VIII.74 |
| **V17** — plano gravado na execução real | **BLOCKER antes do Preview** | — |
| Decomposição do Library Switch (S2) | **BLOCKER antes do Library Switch** | — |
| **V13** — startup da App executa backlog | durante a convergência (Fase 5 migra a capability) | — |
| **V2/V3/V12** — Explorer | durante a convergência | wake-up + bounded wait |
| **V5** — catalog purge | durante a convergência | — |
| Cleanup de quarentena, restore unitário | durante a convergência | wake-up + bounded wait |
| **V10** — `ConversionExecutionRecorder.start` morto | qualquer momento | — |
| **V6** — `OrganizationPlanStore` | sai junto do modelo de preview | modelo |
| Enforcement por port + ArchUnit | **após** a convergência das mutações | ports existirem |

**Nada na coluna "pode esperar após a Fase 5"** — o que sobra depois é o enforcement, que só faz
sentido quando há o que proteger.

## VIII.87 Decisões abertas ao fim do 4.1D

**Fechadas por este slice:** modelo relacional da similaridade (três tabelas, com índice único
parcial); chave de validade de cinco componentes; retenção por alcançabilidade em vez de N; dedup por
chave de validade; publicação `BUILDING` → `ACTIVE` com `SUPERSEDED`; consultas paginadas sem
desserialização; preview em tabela (condicionado ao benchmark); validade do preview como indicação e
não bloqueio; retenção do preview por `CASCADE`; decomposição do library switch em S2; quiesce
mantido como está (já é durável); modelo mínimo de saúde do Worker; política de quatro classes de
resultado; enforcement por **port** em vez de anotação.

**Precisam de decisão sua:**

| # | Pergunta | Por quê |
| --- | --- | --- |
| 1 | **O benchmark do preview (VIII.74) vira micro-slice 4.1E** ou entra no começo da 4.2? | é o único item que decide entre A e C |
| 2 | **A assinatura de exclusões (V15) entra na chave** — confirma? | muda a modelagem de `parameters_digest` |
| 3 | **PHOTO e VIDEO podem agrupar simultaneamente**, ou um limite único para similaridade? | decisão de recurso, não de corretude |
| 4 | **`worker_instance` com quatro campos ou seis?** (versão e `started_at` são úteis mas não mínimos) | escopo de 4.2 |
| 5 | **Enforcement por port**: aceita a refatoração das chamadas diretas para um port de mutação, que é mais invasiva que a anotação e a única que não depende de alguém lembrar de anotar? | define o gate |
| 6 | **A troca de biblioteca incompleta deve ser retomável pela tela** (mostrar e oferecer retomar) ou basta reportar? | escopo de 4.2 |

---

# Fase 4.1E — Representação durável do Organization Preview

Micro-slice de decisão: **A** (plano em tabelas) contra **C** (sumário no banco + itens em NDJSON no
workspace). O 4.1D preferiu A condicionando a benchmark; este slice mede. Nada de produção foi
alterado; o benchmark é código temporário fora de `src/`, descartado ao fim.

## VIII.88 O caminho real, rastreado de novo

| Elo | O que faz |
| --- | --- |
| `OrganizationWebController.preview` | monta `OrganizationExecuteRequest` com `dryRun=true` |
| `OrganizationService.previewAsync` | abre `Execution` `RUNNING` com `executeFlag=false` e delega |
| `OrganizationAsyncRunner.runPreview` | `@Async`, chama `organizationExecutor.execute(request, execution)` |
| `OrganizationExecutor` | roda o laço com todos os efeitos bloqueados e faz `organizationPlanStore.put(executionId, plan)` |
| `OrganizationPlanStore` | `LinkedHashMap` de **5 entradas**, acesso por `executionId` |
| `OrganizationWebController.previewResult` | lê o plano, aplica o filtro "só conflitos", pagina com `subList` |
| `organization.html` | renderiza a página |

### Quantos itens, de fato

**`OrganizationPreviewRequest.MAX_LIMIT = 100_000`** é o teto efetivo — `safeLimit()` corta qualquer
valor acima. O Javadoc do `OrganizationPlanStore` fala em "até centenas de milhares", o que **não é
alcançável pelo código atual**. Registrado como imprecisão de documentação (não é defeito).

A API REST tem um teto próprio e menor: `MAX_INLINE_PREVIEW_LIMIT = 10_000` em
`/api/organization/preview`, que devolve o plano **no corpo da resposta**.

### Quais campos precisam sobreviver

Varredura do template e do controller: a tela usa **seis** campos por item —
`fileName`, `sourcePath`, `targetPath`, `location`, `locationConfidence`, `conflictType` — mais o
booleano `conflict` (filtro) e o `summary` agregado. O `OrganizationItem` tem **21 componentes**.

| Campo | Precisa persistir? | Por quê |
| --- | --- | --- |
| `fileName`, `sourcePath`, `targetPath` | **sim** | renderizados |
| `location`, `locationConfidence`, `conflictType` | **sim** | renderizados |
| `conflict` | **sim** | filtro "só conflitos" |
| `sizeBytes` | **sim** | compõe o total do diálogo de confirmação |
| `catalogFileId` (público) | **sim** | identidade estável para a UI |
| `internalCatalogFileId` | **não** — hoje é `@JsonIgnore` e só o executor usa; o execute recalcula | derivável do `catalogFileId` |
| `yearMonth`, `day`, `category`, `subcategory`, `fileType`, `rule`, `matchReason` | **não** | intermediários do planejamento; nenhum aparece na tela |
| `samePath`, `missingDate`, `targetExists`, `duplicateTarget` | **não** | resumidos em `conflict`/`conflictType` e no `summary` |

**Onze dos vinte e um campos são intermediários.** Isso reduz o volume persistido em ambas as
alternativas e é a razão de o schema do benchmark ter doze colunas, não vinte e uma.

### Acesso necessário

| Necessidade | Existe hoje? |
| --- | --- |
| Acesso aleatório por página | sim — `subList(from, to)` |
| Filtro por conflito | sim — `stream().filter(conflict)` antes de paginar |
| Ordenação alternativa | **não** — a ordem é a do planejamento |
| Reabrir após restart | **não** — o `Map` morre com o processo |
| Uso pelo `execute` | **não** — `execute` recalcula (confirmado de novo em `OrganizationWebController.execute` → `organizationLauncherService.launch`) |

## VIII.89 Critério de decisão, fixado antes de medir

Registrado antes de executar o benchmark:

> **A permanece preferida se:** persistir não dominar o tempo total de geração; o heap continuar
> confortável no orçamento real; primeira página e paginação forem adequadas; o volume no PostgreSQL
> for aceitável.
>
> **C vence se A mostrar custo estrutural em escala real:** pressão de heap, write amplification,
> crescimento excessivo, ou latência de publicação desproporcional para um artefato sequencial.

## VIII.90 Metodologia

**Ambiente:** PostgreSQL 17 em contêiner (a mesma imagem dos testes de integração), JVM com
`-Xmx1g` — o orçamento da App instalada. Itens sintéticos com caminhos e nomes de comprimento
realista, um conflito a cada 300 itens (proporção próxima da observada no comentário do controller:
34 conflitos em ~9 600 itens).

**Cenários:** 10 000, 50 000, 100 000 (**o teto real**) e 200 000 (o dobro do teto, como margem).

**A** — tabela de doze colunas, PK `(plan_id, ordinal)`, índice parcial para conflitos, inserção em
lotes de 5 000 numa transação. **C** — NDJSON escrito em streaming, sem montar o documento inteiro em
memória.

**Duas rodadas.** A primeira mediu paginação por `OFFSET` — que é o que o `subList` de hoje faz
conceitualmente, mas **não é o que uma implementação de produção usaria**, e penalizou A
injustamente. A segunda rodada refez a paginação por *keyset* (`WHERE ordinal >= ?` sobre a PK) e
mediu o heap isoladamente, com a lista mantida alcançável durante a medição.

## VIII.91 Resultados

**Rodada 1 — persistência, volume, leitura e limpeza**

| itens | A persistir | A volume | C persistir | C volume | A conflitos | C conflitos | A limpar | C limpar |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 000 | 192 ms | 2,9 MB | 97 ms | 3,9 MB | 1 ms | 26 ms | 4 ms | 0 ms |
| 50 000 | 794 ms | 17,4 MB | 94 ms | 19,9 MB | 2 ms | 10 ms | 16 ms | 2 ms |
| **100 000** | **1 497 ms** | **46,4 MB** | **130 ms** | **40,0 MB** | **1 ms** | **8 ms** | 35 ms | 3 ms |
| 200 000 | 3 312 ms | 104,5 MB | 239 ms | 80,4 MB | 1 ms | 6 ms | 72 ms | 6 ms |

**Rodada 2 — heap dos itens em memória**

| itens | heap retido | por item |
| --- | --- | --- |
| 10 000 | 3,4 MB | ~344 B |
| 50 000 | 16,9 MB | ~338 B |
| **100 000** | **34,3 MB** | ~344 B |
| 200 000 | 68,9 MB | ~344 B |

**Rodada 2 — paginação justa: keyset (A) contra varredura sequencial (C)**

| itens | A 1ª pág. | A meio | A fim | C 1ª pág. | C meio | C fim |
| --- | --- | --- | --- | --- | --- | --- |
| 10 000 | 6 ms | 3 ms | 1 ms | 2 ms | 13 ms | 20 ms |
| 50 000 | 17 ms | 8 ms | 0 ms | 1 ms | 20 ms | 30 ms |
| **100 000** | **1 ms** | **0 ms** | **0 ms** | **0 ms** | **31 ms** | **57 ms** |
| 200 000 | 1 ms | 0 ms | 0 ms | 0 ms | 39 ms | 72 ms |

*(os valores altos de A nos dois primeiros cenários são aquecimento do plano de consulta; a partir de
100 000 o comportamento estabiliza)*

### Leitura dos números

1. **C persiste ~11× mais rápido** no teto real (130 ms × 1 497 ms). É o único critério em que C
   ganha de forma expressiva, e é um custo pago **uma vez por preview**.
2. **A pagina de 30× a 70× mais rápido** fora da primeira página (0 ms × 31–57 ms em 100 000), e a
   vantagem **cresce** com a posição da página — enquanto a de C **piora**, porque cada página exige
   varrer o arquivo desde o início.
3. **A filtra conflitos em 1 ms**; C leva 6–26 ms varrendo. Este é o caso de uso que o próprio código
   documenta como essencial ("encontrar 34 conflitos entre ~9 600 itens paginando é inviável").
4. **Volume praticamente empatado** (46 MB × 40 MB em 100 000): não há write amplification relevante.
5. **O heap é o mesmo para as duas**, porque o planner produz a lista inteira antes de qualquer
   persistência. **Não desempata** — mas quantifica o problema atual: 34 MB por plano de 100 000, e o
   `Map` guarda cinco, ou seja **até ~171 MB** de planos num heap de 1 GB.

## VIII.92 Decisão: **A**

**A vence**, e vence pelo que o usuário realmente faz com o plano: **paginar e filtrar conflitos**.
C troca um ganho pago uma vez (1,4 s a menos ao publicar) por um custo pago a cada interação
(30–70 ms por página, crescendo com a posição, mais 8 ms por filtro). Numa tela em que a navegação é
o uso principal, essa troca é ruim.

Nenhuma condição de C se materializou: não houve pressão de heap distinguível (o heap é da produção,
igual nos dois), não houve write amplification, o volume é comparável, e 1,5 s de publicação não é
desproporcional para uma operação que percorre o catálogo inteiro.

**Força da decisão: forte nos critérios medidos, com uma incerteza material declarada** — ver
VIII.93.

**O que a decisão dispensa:** a alternativa híbrida deixa de ser necessária. Nenhum dos dois motivos
que a justificariam (heap, latência de publicação) apareceu.
## VIII.93 Limitações do benchmark

Declaradas para que a decisão possa ser revista se alguma delas se mostrar material.

| # | Limitação | Efeito sobre a decisão |
| --- | --- | --- |
| 1 | **O tempo de *produzir* o plano não foi medido.** O critério "persistir não domina" precisa desse denominador, e ele exigiria um catálogo real de 100 000 entradas com regras, datas e localização | **é a incerteza material.** Se produzir 100 000 itens levar menos de ~5 s, persistir passaria a ser ≥ 23% do total e o critério ficaria apertado. A evidência indireta é que o planner consulta o catálogo paginado e resolve regras, data e localização por item — mas indireta é o que ela é |
| 2 | Itens sintéticos com caminhos de comprimento fixo | caminhos reais variam; afeta volume e heap proporcionalmente, não a ordem de grandeza |
| 3 | Um conflito a cada 300 itens | a vantagem de A no filtro **cresce** quanto mais raro o conflito, que é o caso real |
| 4 | PostgreSQL em contêiner, sem a carga da aplicação em volta | ambos os lados medidos no mesmo ambiente; o viés é simétrico |
| 5 | Heap medido por diferença de alocação com `System.gc()` | ordem de grandeza confiável (~344 B/item, coerente com 12 campos e 4 strings), valor exato não |
| 6 | NDJSON lido por varredura sequencial | é o comportamento honesto de um arquivo sem índice. Um índice de offsets tornaria C competitivo na paginação — mas seria reimplementar em arquivo o que a PK já dá |
| 7 | Sem concorrência: um preview por vez | o cenário real de dois previews simultâneos favorece A (o banco serializa) menos que C (arquivos independentes). Não medido |

**Premissa que precisa ser validada antes da implementação:** o tempo de produção do plano em escala
real (limitação 1). Um cronômetro em torno de `organizationPlanner.preview` durante um preview de
biblioteca grande basta — não exige benchmark novo.

## VIII.94 Modelo conceitual da alternativa escolhida

### `organization_plan` — o cabeçalho

| Campo | Papel |
| --- | --- |
| `execution_id` bigint **PK**, FK → `execution` **ON DELETE CASCADE** | o plano é da execução que o produziu |
| `source_path`, `target_path`, `layout` | o que foi pedido |
| `status` varchar(12) | `BUILDING` / `READY` / `FAILED` |
| `item_count`, `conflict_count`, `planned_moves`, `total_size_bytes` | o `summary`, para a tela não contar |
| `catalog_signature` varchar(120) | estado do catálogo observado — ver VIII.96 |
| `built_at` timestamp | quando ficou `READY` |

A PK ser o próprio `execution_id` diz o essencial: **um plano por execução, e o plano não existe sem
ela**.

### `organization_plan_item` — as linhas

| Campo | Papel |
| --- | --- |
| `execution_id` bigint, `ordinal` integer — **PK composta** | ordem do planejamento; é a chave da paginação por keyset |
| `catalog_file_id` uuid | identidade estável para a UI |
| `file_name`, `source_path`, `target_path` | renderizados |
| `location`, `location_confidence` | renderizados |
| `conflict` boolean, `conflict_type` varchar(40) | filtro e rótulo |
| `size_bytes` bigint | compõe o total |

**Índices:** a PK `(execution_id, ordinal)` serve a paginação; um índice parcial
`(execution_id, ordinal) WHERE conflict` serve o filtro "só conflitos" — que o benchmark mediu em
1 ms.

**Onze campos do `OrganizationItem` não persistem** (VIII.88): são intermediários do planejamento.
`internalCatalogFileId` fica de fora porque o `execute` recalcula e não lê o plano.

### Publicação e estados

```
INSERT plano BUILDING → grava itens em lote → verifica item_count → UPDATE para READY
```

A tela **só considera `READY`**. Um plano `BUILDING` cuja execução terminou é resíduo de falha:
identificável (status + estado terminal da execução) e limpável. Não é preciso `EXPIRED`: um plano
não expira por tempo, ele deixa de ser o mais recente — e a tela sempre pede o plano de **uma
execução**, nunca "o último plano".

**Atomicidade:** a transição para `READY` num único `UPDATE` é o ponto de publicação. Nada parcial é
visível porque a consulta filtra por status.

## VIII.95 Lifecycle e cleanup

| Situação | Comportamento |
| --- | --- |
| Execução apagada por retenção | plano e itens somem por `CASCADE` — sem código |
| Crash durante a gravação | plano fica `BUILDING`; a execução fica `INTERRUPTED`; ambos removidos pela retenção |
| Preview cancelado | idem |
| Restart da App | nada a fazer: o plano é do banco |
| Restart do Worker | idem |
| Vários previews | um por execução; não competem |
| Limpeza dedicada | **desnecessária** — o `CASCADE` cobre; um plano `BUILDING` órfão só existe enquanto a execução existir |

Isto é o argumento operacional que a comparação com C esconde: **A não precisa de limpeza própria**,
enquanto um artefato em workspace herdaria a mesma classe de problema que o `ConversionWorkspaceCleaner`
resolve hoje para a conversão.

## VIII.96 Contrato de validade — e o que ele **não** muda

Confirmado no código: `execute` chama `organizationLauncherService.launch`, que enfileira um comando
com os parâmetros do formulário; o handler roda `organizationPlanner.preview` de novo. **O execute
não lê o plano.**

> **Contrato:** o preview mostra o que seria feito com o estado observado no momento em que foi
> produzido; o execute recalcula sob o estado atual. Divergência entre os dois é consequência normal
> da biblioteca ter mudado, não corrupção.

- `preview_execution_id` no execute: **referência informativa**, opcional, para a tela poder dizer
  "você viu o plano X". Nunca condição.
- **`execute` MUST NOT depender da existência do plano** — e hoje não depende.
- `catalog_signature` (contagem + maior `updatedAt` das entradas sob a origem) serve para a tela
  **indicar** "o catálogo mudou desde este plano". **Indicação, nunca bloqueio.**

**Nada disso altera o que o preview significa para o usuário**: mesmos itens, mesmos conflitos, mesma
relação com o execute. O que muda é onde o plano fica guardado.

## VIII.97 Regras funcionais que a arquitetura tensiona — para sua revisão

Revisando a Parte VIII inteira à luz de "arquitetura muda onde a regra executa, não o que ela
significa", três conclusões anteriores **alteram comportamento funcional** e não devem ser tratadas
como fechadas.

### RF-1 — A API REST de similaridade deixaria de calcular sob demanda

| | |
| --- | --- |
| **Comportamento atual** | `GET /api/duplicates/similar-photos` e `/similar-videos` **calculam** o agrupamento se o cache estiver frio e devolvem os grupos na mesma resposta. É um contrato de API pública documentado no OpenAPI |
| **Conflito** | calcular dentro da requisição é o segundo motor (V4); e com o resultado durável, quem calcula é o Worker |
| **Alternativas para preservar** | (a) o endpoint **enfileira** e aguarda pelo orçamento curto, devolvendo os grupos se terminar a tempo — preserva o contrato na maioria dos casos, mas não em bibliotecas grandes; (b) devolve `202 Accepted` com a referência da execução quando não houver resultado válido; (c) mantém o cálculo síncrono só na API — **rejeitada**, é o segundo motor |
| **Mudança proposta** | (a) + (b): calcula-se se já houver resultado válido; caso contrário enfileira e espera; se estourar, `202` com o id |
| **Impacto de UX** | a tela não muda (já usa o caminho assíncrono). **Um cliente de API existente passa a poder receber `202`** |
| **Precisa da sua aprovação** | **sim** — é alteração de contrato de API pública |

### RF-2 — A API REST de preview e o export

| | |
| --- | --- |
| **Comportamento atual** | `POST /api/organization/preview` calcula e devolve o plano **no corpo** (até 10 000 itens); `POST /api/organization/preview/export` recalcula e devolve um ZIP |
| **Conflito** | o preview passa a ser E6b executado pelo Worker; calcular dentro da requisição seria o mesmo padrão de RF-1 |
| **Alternativas para preservar** | (a) o endpoint enfileira, espera pelo orçamento e devolve o plano — com 10 000 itens é plausível terminar dentro dele; (b) `202` com referência quando não terminar; (c) manter os dois endpoints síncronos como exceção — **rejeitada** pelo mesmo motivo |
| **Mudança proposta** | (a) + (b), e o export lendo o plano **persistido** em vez de recalcular |
| **Impacto de UX** | nenhum na tela; a API pode responder `202` |
| **Precisa da sua aprovação** | **sim** |

### RF-3 — Operações do Explorer e da quarentena passam a poder responder "em andamento"

| | |
| --- | --- |
| **Comportamento atual** | rename, quarentenar, apagar, restore unitário e limpeza de ausentes respondem o **resultado final** na mesma requisição |
| **Conflito** | com o Worker executando, o resultado pode não estar pronto dentro do orçamento |
| **Status** | **já aprovado por você no 4.1C** ("a App pode responder 'em andamento'... não existe requisito arquitetural de rename ser sempre concluído dentro da própria requisição HTTP") |
| **Observação** | registrado aqui só para constar; **não** é decisão pendente |

### O que **não** muda (verificado)

- O preview mostra os mesmos itens, os mesmos conflitos e o mesmo resumo.
- O filtro "só conflitos" e a paginação continuam existindo, com o mesmo significado.
- O execute continua recalculando — não é mudança, é o comportamento atual.
- A quarentena continua oferecendo restaurar e expurgar, com os mesmos diálogos.
- A similaridade continua tendo limiar ajustável e os mesmos agrupamentos.
- A troca de biblioteca continua cancelando o que roda, limpando o catálogo antigo e reconfigurando
  o watcher.

## VIII.98 Novos achados do 4.1E

| # | Achado | Evidência | Severidade |
| --- | --- | --- | --- |
| **V18** | O Javadoc do `OrganizationPlanStore` afirma "até centenas de milhares de itens", mas `OrganizationPreviewRequest.MAX_LIMIT` corta o plano em **100 000**. Documentação diverge do código | VIII.88 | baixa (imprecisão, não defeito) |
| **V19** | O `OrganizationPlanStore` guarda até **5 planos**, e o benchmark mediu ~344 B por item: no teto de 100 000 itens são **~171 MB** de heap retido por planos numa App com `-Xmx1g`. O teto de 5 nunca foi dimensionado contra o teto de itens | VIII.91 | **média** — pressão de heap real, hoje |

V18 e V19 somam-se a V1–V17. **Nenhum corrigido neste slice.**

## VIII.99 A Fase 4.1 está fechada?

**Sim, do ponto de vista arquitetural**, com três ressalvas registradas:

| Item | Situação |
| --- | --- |
| Fronteira App × Worker (B′) | fechada em 4.1A/4.1B |
| Protocolo de comunicação e espera | fechado em 4.1C |
| Modelos duráveis (similaridade, preview, library switch) | fechados em 4.1D/4.1E |
| **RF-1 e RF-2** | **abertas — dependem da sua aprovação**, por mudarem contrato de API |
| Tempo de produção do plano (limitação 1) | premissa a validar, não bloqueia a decisão |
| Orçamento de espera, retenção de agrupamentos, campos de `worker_instance` | tuning, decidido na implementação |

## VIII.100 Decomposição proposta da Fase 4.2 — para sua autorização

Não iniciada. Ordem derivada das dependências, não do custo.

| Slice | Conteúdo | Por que nesta posição |
| --- | --- | --- |
| **4.2.1** | **Defeitos que não dependem de nada**: V1, V11, V10, V17, V19 | são defeitos ativos; V1 e V11 bloqueiam qualquer migração |
| **4.2.2** | **Protocolo**: wake-up (`LISTEN/NOTIFY` + polling adaptativo), bounded wait, `worker_instance` | é pré-requisito de toda migração interativa |
| **4.2.3** | **Port de mutação da biblioteca** + primeiras regras de ArchUnit | precisa existir antes de as mutações migrarem, para que migrem já corretas |
| **4.2.4** | **Explorer** (rename arquivo, rename pasta, quarentenar, apagar) + **V2, V3, V12** | depende de 4.2.2 e 4.2.3 |
| **4.2.5** | **Quarentena**: restore unitário e limpeza de ausentes; **catalog purge** | mesma dependência, menor risco |
| **4.2.6** | **Preview durável** (modelo de VIII.94) + **V6, V18** + resposta de RF-2 | independente do Explorer; depende da decisão RF-2 |
| **4.2.7** | **Similaridade durável** (M3) + **V4, V8, V9, V15, V16** + resposta de RF-1 | o mais complexo; depende da decisão RF-1 |
| **4.2.8** | **Library switch** decomposto (S2) | depende do protocolo e do port |

**Nenhum slice termina com dois motores para a mesma capability**, e cada um deles é independente dos
seguintes — se você quiser parar depois de qualquer um, o sistema fica coerente.

**A Fase 5** (backlogs, rebuilds, dataset geográfico) só começa depois de 4.2.7, porque a similaridade
define o padrão que os sete workloads vão seguir.

## VIII.101 Decisões que dependem de você

| # | Pergunta | Bloqueia |
| --- | --- | --- |
| 1 | **RF-1** — a API de similaridade pode responder `202` quando não houver resultado válido? | 4.2.7 |
| 2 | **RF-2** — a API de preview e o export podem responder `202`? E o export pode ler o plano persistido em vez de recalcular? | 4.2.6 |
| 3 | A decomposição da 4.2 em oito slices está correta, ou você prefere outro agrupamento? | 4.2 |
| 4 | Medir o tempo de produção do plano (limitação 1) antes da 4.2.6, ou aceitar a premissa? | 4.2.6 |
| 5 | Autoriza iniciar a 4.2.1? | — |

---

# Fase 4.1F — Consolidação, fronteiras funcionais e readiness por item

Este slice revisa 4.1A–4.1E à luz de esclarecimentos do dono do produto, separa arquitetura de regra
funcional, incorpora readiness por arquivo/capability e consolida o contrato final. A Parte VIII
**não** é imutável: onde uma conclusão anterior for rebaixada ou corrigida, o registro fica em
VIII.102.

**Princípio desta revisão:** preservar o *o quê*; redesenhar o *onde* e o *como*.

## VIII.102 Reclassificação retroativa das conclusões 4.1A–4.1E

Cada conclusão anterior reclassificada como **A** arquitetura · **B** regra funcional existente ·
**C** proposta de mudança funcional (precisa de aprovação) · **D** tuning · **E** premissa ·
**F** violação/dívida.

| Conclusão | Onde | Era tratada como | **Agora é** | Observação |
| --- | --- | --- | --- | --- |
| B′: Worker é executor exclusivo | 4.1A | decisão | **A** | mantida |
| App pode aguardar por orçamento curto | 4.1C | decisão | **A** (a capacidade) + **D** (o orçamento) | **mas ver a correção abaixo** |
| "Toda operação interativa espera" | 4.1C | implícito | **corrigido** | esperar é decisão de UX **por capability**, não obrigação |
| `LISTEN/NOTIFY` + polling | 4.1C | decisão | **A** | mantida |
| Camadas de resultado durável | 4.1C | decisão | **A** | mantida |
| M3 para similaridade | 4.1D | decisão | **A** | mantida, com a chave revista em VIII.106 |
| "A consulta nunca computa" (similaridade) | 4.1C/4.1D | **MUST arquitetural** | **A** (não computar na requisição) + **C** (o que a API responde) | estavam misturados |
| **RF-1** (API de similaridade) | 4.1E | proposta | **C — e agora com UX esclarecida** | ver VIII.106 |
| **RF-2** (API de preview) | 4.1E | proposta | **C** | ver VIII.107 |
| **RF-3** (Explorer "em andamento") | 4.1E | aprovado | **C aprovado** | mantido |
| Preview vai ao Worker (E6b) | 4.1C | decisão | **A** | mantida |
| Preview em tabelas (A) | 4.1E | decisão | **A** | mantida, benchmark preserva |
| Preview é evidência, execute recalcula | 4.1C/4.1D | decisão | **B — regra funcional existente** | não é decisão nova: é o comportamento atual, e deve ser preservado |
| Retenção do preview por `CASCADE` | 4.1D | decisão | **corrigida** | ver VIII.107: preview é **efêmero**, com TTL próprio menor que o da execução |
| Retenção da similaridade por alcançabilidade | 4.1D | decisão | **A**, com correção | ver VIII.106: alcançabilidade não pode invalidar o que o usuário está olhando |
| Quiesce já é durável (advisory lock) | 4.1D | achado | **A** | mantida |
| `worker_instance` seis campos | 4.1D | decisão | **A** (existir) + **D** (quais campos) | mantida |
| Port em vez de anotação | 4.1D | decisão | **A** | refinada em VIII.108 |
| Library switch decomposto (S2) | 4.1D | decisão | **A** | mantida |
| Sequência de oito slices da 4.2 | 4.1E | proposta | **substituída** | ver VIII.112 |

**A correção mais importante:** 4.1C generalizou o *bounded wait* como se toda operação interativa
devesse esperar. Não deve. Esperar é uma escolha de UX **por capability** — e para a similaridade,
como o dono do produto esclareceu, **não esperar é o comportamento certo**.

## VIII.103 Readiness por arquivo/capability — investigação

**Descoberta central: a readiness por arquivo já existe no produto, derivada de fatos, e já é usada
na camada de acesso a dados.** Nenhuma capability espera a biblioteca inteira ficar pronta.

| Capability | Pré-condição por arquivo | Como é expressa hoje | Arquivo não elegível |
| --- | --- | --- | --- |
| Explorer (navegar, renomear) | existir no disco | leitura direta do filesystem | não aparece |
| Explorer (quarentenar) | estar catalogado e ativo | `catalogedUnder(target)` filtra | recusa com motivo |
| Duplicado exato | ter `sha256` | `findDuplicateGroups` agrupa por sha | fora do agrupamento |
| **PHOTO similarity** | ter `MediaFingerprint(PHOTO_PHASH, algoritmo)` **e** `lifecycleStatus = ACTIVE` | `findFingerprintedPhotos` já filtra por ambos | **fora do conjunto — sem bloquear ninguém** |
| **VIDEO similarity** | ter frames de fingerprint do algoritmo ativo | `findFingerprintedVideoFrames` idem | idem |
| Organization preview/execute | estar catalogado; data é **desejável, não obrigatória** | item entra no plano com `missingDate` | **entra mesmo assim**, sinalizado |
| Conversão | ativo, `fileType = VIDEO`, não ser já HEVC/MP4 | `ineligibilityOf` | pulado, com motivo por item |
| Metadata rebuild | estar catalogado | filtro da consulta | fora do conjunto |
| Geolocation rebuild | ter coordenadas | filtro da consulta | fora do conjunto |
| Quarentena | ter `movement` em `MOVED` com razão de quarentena | filtro da consulta | fora da lista |

**Conclusões:**

1. **Não é preciso criar `file.ready`.** Readiness é derivada, é diferente por capability, e o
   produto já a calcula onde importa: na consulta que monta o conjunto de trabalho.
2. **Não é preciso um novo estado por arquivo.** O que torna A elegível para similaridade de foto é a
   existência do fingerprint dela — um fato, não um flag.
3. **A similaridade já opera sobre o conjunto elegível.** Ela agrupa quem tem fingerprint; quem não
   tem simplesmente não entra. O exemplo do enunciado (98 000 prontas, 2 000 processando) **já é o
   comportamento atual do cálculo**.

**Então onde está o problema?** Não na leitura dos dados — na camada de cima. Ver VIII.104.

**Princípio a registrar** (derivado, não inventado):

> **Readiness é por arquivo e por capability, e deve ser derivada de fatos existentes.** Uma
> capability trabalha sobre o conjunto elegível no instante em que começa. A entrada posterior de um
> arquivo não invalida o trabalho já feito; produz, no máximo, a oportunidade de um novo trabalho.

## VIII.104 O bloqueio global do inventário — investigação

Rastreado a partir de `InventoryRunningState`, `FingerprintBacklogEngine.inventoryActive()` e de todo
consumidor.

### O que o inventário bloqueia hoje

| Lugar | O que é recusado/escondido | Justificativa aparente | Veredito |
| --- | --- | --- | --- |
| `DuplicatesWebController` | **a tela inteira de duplicados é esvaziada** (`Page.empty()`) | resultados seriam parciais | **injustificado — é leitura** |
| `SettingsBackupWebController.restoreBackup` | restaurar backup | o restore derruba o banco | **legítimo, mas subespecificado**: deveria bloquear com **qualquer** execução, não só inventário |
| `SettingsMetadataWebController` | rebuild de metadata | escreve no catálogo que o inventário está escrevendo | **plausível**, mas o escopo certo é por arquivo/pasta, não global |
| `SettingsGeodataWebController` (4 pontos) | dataset geográfico, rebuild de localização | o inventário resolve localização | **plausível**, mesmo comentário |
| `SettingsToolsWebController.installTools` | instalar ferramentas externas | o inventário usa ffprobe | **plausível** |
| `SettingsParameterWebController.update` | **qualquer** alteração de configuração | algumas mudanças afetam o inventário em curso | **amplo demais**: bloqueia até preferências de tela |
| `GeoDatasetAutoUpdateScheduler` | a passagem automática do dataset | evitar competir com o inventário | **é adiamento, não bloqueio** — aceitável |

### Por que isso existe

Todos esses guardas nasceram quando **inventário e tudo o mais rodavam no mesmo processo, no mesmo
heap, sem locks de caminho e sem fila**. Bloquear globalmente era a única exclusão disponível. Com
`OperationLock` por caminho, claim, lease e posse, a exclusão passou a existir no nível certo — e os
guardas globais permaneceram.

**Nenhum deles protege uma invariante que os mecanismos atuais não protejam melhor**, com uma
exceção: o restore de backup, que derruba o banco e portanto precisa de exclusão ampla — e que, por
isso mesmo, deveria usar a janela de manutenção em vez de perguntar por inventário.

### Dois defeitos, registrados sem correção

**V20 — a tela de duplicados fica vazia durante qualquer inventário.**
`DuplicatesWebController` devolve `Page.empty()` enquanto `inventoryActive()`. É **leitura de um
resultado já calculado** sobre arquivos que podem não ter relação nenhuma com o inventário em curso.
Numa biblioteca viva com backup automático de celular, o inventário roda com frequência — e a tela
fica indisponível junto. Contradiz diretamente o princípio de readiness por arquivo, que a própria
consulta de fingerprints já respeita. **Severidade: alta** (perda de capacidade em uso normal).

**V21 — os guardas por "inventário ativo" são não-determinísticos sob concorrência.**
`InventoryRunningState.isRunning()` chama `executionQueryService.active()`, que devolve **a execução
ativa mais recente** (`findFirstByFinishedAtIsNullAndStatusInOrderByStartedAtDesc`) e compara o tipo
com `INVENTORY`. Com o Worker executando várias execuções em paralelo — que é o desenho a partir da
Fase 4 —, um inventário em curso **deixa de ser detectado** se outra execução tiver começado depois
dele. O guarda passa a proteger ou não conforme a ordem de início. Foi correto quando havia uma
execução por vez; deixou de ser. **Severidade: média** (o guarda falha aberto, e a maioria dos casos
que ele protege é injustificada de todo modo).

## VIII.105 Princípio: processamento contínuo não implica indisponibilidade global

> **O processamento é contínuo; a disponibilidade não pode ser global.**
>
> Trabalho pendente sobre o arquivo X **MUST NOT** impedir uma operação independente sobre o arquivo
> Y que já satisfaz as próprias pré-condições. O escopo de exclusão **MUST** ser o menor que proteja
> a invariante concreta em jogo — o caminho, o arquivo, o conjunto elegível — e nunca "a biblioteca".
>
> Exclusão global só é admissível quando a invariante exige estado global estável. Hoje há **duas**:
> a troca de biblioteca (muda qual é a biblioteca) e o restore de backup (substitui o banco). Ambas
> já têm a primitiva certa: a janela de manutenção.
>
> "Inventário em andamento", "backlog pendente", "fingerprint faltando" e "metadata incompleta"
> **MUST NOT** ser usados como razão para indisponibilizar capabilities inteiras.
## VIII.106 Similaridade, revisada com a UX real

### O que muda com o esclarecimento do produto

O uso real é: entrar na tela, ver os grupos, escolher, apagar, voltar depois. **O usuário não precisa
que uma análise recém-iniciada termine naquela interação**, e não é problema se uma duplicata
recém-descoberta ainda não aparecer.

Isso invalida uma premissa implícita de 4.1C/4.1E: a de que a similaridade precisaria de espera
síncrona ou de resposta imediata. **Não precisa.** É o caso mais claro de capability em que a App
**não** espera.

### Ciclo de vida do agrupamento — corrigido

4.1D dizia que um agrupamento cuja assinatura mudou é "inalcançável e apagável a qualquer momento".
**Isso está errado do ponto de vista funcional**: apagaria o resultado que o usuário está analisando
no exato momento em que uma foto nova é fingerprintada — o que, com backup automático de celular,
acontece o tempo todo.

Correção, separando três conceitos que estavam colapsados:

| Conceito | Definição | Consequência |
| --- | --- | --- |
| **Válido para nova análise** | a chave corrente casa com a do agrupamento | se não casa, uma nova análise **pode** ser oferecida |
| **Apresentável** | está `ACTIVE` e seus membros ainda existem | **continua sendo mostrado**, mesmo com a chave desatualizada |
| **Descartável** | foi substituído por um agrupamento mais novo da mesma família, ou expirou | só então é apagado |

**Estados:** `BUILDING` → `ACTIVE` → `SUPERSEDED`. A tela consome **apenas `ACTIVE`**, nunca
`BUILDING`. Um `ACTIVE` cuja assinatura envelheceu **permanece `ACTIVE`** e apresentável; a tela pode
indicar "há fotos novas desde esta análise" e oferecer recalcular. Só vira `SUPERSEDED` quando um
agrupamento novo da mesma família (mesmo tipo, mesmo limiar) é publicado.

**Isso elimina a perseguição da biblioteca em movimento**: o resultado não persegue o estado, ele
representa um instante e diz de quando é.

### A chave, revisada

A chave de cinco componentes de 4.1D continua, com duas correções:

| Componente | Correção |
| --- | --- |
| `fingerprint_signature` | representa **o conjunto elegível no momento do cálculo**, não "a biblioteca". Continua sendo a tripla de `(kind, algorithm)`, que **já é** o conjunto elegível — a consulta filtra por fingerprint existente e `lifecycleStatus = ACTIVE` |
| assinatura de exclusões (V15) | entra no `parameters_digest` |

E a **função da chave muda**: em 4.1D ela decidia *o que apagar*; agora decide **se vale recalcular** e
**se a tela deve dizer que há novidade**. Apagar passa a ser decidido por supersessão e por TTL, não
por assinatura.

**Retenção corrigida:** manter o `ACTIVE` de cada família `(media_type, limiar)`; apagar o
`SUPERSEDED` na publicação do sucessor; apagar `BUILDING` órfão por prazo. Sem TTL para o `ACTIVE` —
ele vale até ser substituído.

### RF-1, revisado

| | |
| --- | --- |
| **Comportamento atual** | `GET /api/duplicates/similar-photos` e `/similar-videos` calculam sob demanda no cache miss e devolvem os grupos |
| **Consumidores conhecidos no código** | apenas o próprio produto: a **tela usa o caminho assíncrono** (`PhotoSimilarityAsyncRunner` + `cachedPage`), não estes endpoints. A varredura não encontra nenhum consumidor interno de `/api/duplicates/similar-*`; eles existem como **API pública documentada no OpenAPI** |
| **Conflito arquitetural** | calcular na requisição é o segundo motor (V4) |
| **Alternativas** | (a) endpoint devolve o `ACTIVE` se houver, e `202` com a referência da execução se não houver; (b) endpoint enfileira e **aguarda** — rejeitada: contraria a UX esclarecida e prenderia a requisição por minutos; (c) manter o cálculo síncrono — rejeitada: é o segundo motor |
| **Proposta** | **(a)** |
| **Impacto na tela do Nimbus** | **nenhum** — ela já não usa esses endpoints |
| **Impacto na API** | um cliente externo que hoje sempre recebe grupos passará a receber `202` quando não houver análise válida |
| **Classificação** | **C — proposta de mudança funcional, aguardando sua aprovação** |

**Observação relevante para a decisão:** como a tela não usa esses endpoints, o risco de RF-1 é
inteiramente sobre consumidores externos — que, sendo esta uma aplicação desktop de uso pessoal,
podem não existir. Você é quem sabe se alguém consome a API.

## VIII.107 Preview, revisado

### Preview é efêmero — nova classificação

Decisão funcional do dono do produto, incorporada: **o preview não é histórico de domínio**. Ele
existe para o usuário olhar e decidir. Persistir é meio, não fim: serve para atravessar a fronteira
App × Worker, sobreviver a restart, permitir publicação atômica, tirar o plano do heap da App e
permitir paginação e filtro eficientes.

**Isso corrige 4.1D**, que classificou o plano como "operacional descartável **com a mesma retenção
da execução**". A retenção da execução é longa (é histórico); a do plano deve ser curta.

| | Execução `ORGANIZATION` (preview) | Plano e itens |
| --- | --- | --- |
| Natureza | histórico: "foi pedido um preview" | artefato para olhar |
| Retenção | a política de execuções, já existente | **TTL curto próprio** |
| Ao expirar | permanece | itens apagados; a execução continua no histórico |

**Modelo:** `organization_plan` ganha `expires_at`. Um plano expirado é removido com seus itens por
varredura, **sem tocar na execução**. A tela, ao pedir um plano expirado, responde o que já responde
hoje quando o `Map` esqueceu: "o plano não está mais aqui" — comportamento existente, preservado.

**Arquitetura × tuning:**
- **Arquitetura:** o preview é efêmero e tem TTL próprio, menor que o da execução.
- **Tuning:** o valor. A intuição do produto é "não mais que um dia, talvez menos". Um default de
  **12 horas** é razoável — cobre a sessão de trabalho e a retomada no mesmo dia — mas **é tuning**,
  e deve ser configurável se houver motivo. Não vira constante arquitetural.

### RF-2, revisado

| | |
| --- | --- |
| **Comportamento atual** | `POST /api/organization/preview` calcula e devolve o plano no corpo (teto de 10 000); `POST /preview/export` recalcula e devolve ZIP |
| **UX esclarecida** | para o usuário tanto faz se o POST devolve o preview ou inicia sua geração, desde que a tela mostre "gerando…" e apresente quando ficar pronto |
| **Consumidores no código** | a tela usa o fluxo MVC (`previewAsync` + `previewResult`), **não** o endpoint REST. O REST é API pública |
| **Proposta** | o endpoint enfileira e devolve a referência (`202`); o export lê o plano persistido em vez de recalcular |
| **Impacto na tela** | **nenhum** — muda apenas de onde ela lê o plano |
| **Impacto na API** | observável: de "plano no corpo" para "referência" |
| **Classificação** | **C — proposta de mudança funcional, aguardando sua aprovação** |

### O que permanece intocado

`execute` continua recalculando o plano; o preview continua sendo informativo;
`preview_execution_id`, se existir, é referência de auditoria/UX e **nunca** pré-condição. Isso é
**regra funcional existente (B)**, preservada — não decisão nova.

## VIII.108 Enforcement: contrato final do port

Refinando a decisão de 4.1D (port em vez de anotação).

**O port:** uma interface por *efeito de biblioteca*, não uma por classe. Duas bastam para cobrir
tudo o que a matriz de efeitos chama de E1:

| Port | Operações | Quem chama hoje |
| --- | --- | --- |
| `LibraryFileMutations` | mover com verificação, renomear, apagar arquivo, apagar diretório vazio | `SecureFileMove` (já é quase isto), `EmptyDirectoryCleaner`, `QuarantinePurgeService`, `ExplorerDeletionService`, `ExplorerRenameService` |
| `CatalogMutations` | as escritas em massa sobre o catálogo da coleção (E2) | `ReconcileApplier`, `CatalogFileRetentionService`, `QuarantinePurgePersistence`, rebuilds |

**Quem pode implementá-lo:** uma única classe por port, em `shared/infrastructure` ou no domínio
dono, sem alternativas — a implementação é o *choke point*.

**Quem pode injetá-lo:** apenas classes alcançáveis a partir de um `ExecutionJobHandler`. É isto que
ArchUnit verifica, e é a diferença essencial em relação à anotação: **a capacidade é concedida por
injeção, não declarada por quem escreve o código**.

**Como o Worker ganha a capacidade:** por estar no grafo de dependências de um handler. **Como a App
fica impedida:** uma classe da App que injetasse o port seria alcançável a partir de um controller, e
a regra falha. Não depende de ninguém lembrar de nada.

**O que fica legitimamente fora do port:**

| Fora | Por quê |
| --- | --- |
| Escrita em workspace/cache (E3) | não é biblioteca; miniatura, temporário de conversão, arquivo decodificável |
| Infraestrutura (E10) | cluster embarcado, criação de workspace, limpeza de temporários órfãos |
| Manutenção da instalação (E8) | ffmpeg, instalador, `pg_dump`/`pg_restore` |
| Leitura | listar, sondar, ler bytes |

**Regras de ArchUnit resultantes:**

| R | Regra |
| --- | --- |
| P1 | apenas a implementação declarada pode chamar `Files.move`/`Files.delete`/`Files.write` sobre caminhos da biblioteca |
| P2 | apenas classes alcançáveis a partir de um `ExecutionJobHandler` podem depender de `LibraryFileMutations` ou `CatalogMutations` |
| P3 | nenhuma classe de `..infrastructure.web..`/`..rest..` alcança os ports, direta ou transitivamente |
| P4 | a lista de classes autorizadas a chamar `Files.*` fora dos ports (workspace, infraestrutura) é fechada e explícita no teste |

P4 é o ponto de manutenção honesto: a lista existe e cresce por decisão consciente, revisada no
próprio teste — que é o oposto de uma anotação espalhada.
## VIII.109 Matriz consolidada de capabilities

**Ef.** = categoria de efeito · **Exec?** = precisa de `Execution` · **Dur.** = resultado durável ·
**Efêm.** = resultado efêmero (TTL próprio) · **Own?** = posse verificada no commit ·
**c/ Inv.** = pode coexistir com inventário em curso · **Espera?** = a UX espera o resultado.

| Capability | Executor alvo | Ef. | Exec? | Dur. | Efêm. | Readiness | Escopo de lock | Own? | c/ Inv. | Espera? | Hoje | Dívida |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Inventário | Worker | E2 | sim | linha | — | pasta existe | árvore | n/a | n/a | não | Worker | — |
| Reconcile agendado | Worker | E2 | sim | linha | — | pasta existe | árvore | n/a | **sim** | não | Worker | — |
| **Reconcile reativo** | Worker | E2 | sim | linha | — | idem | árvore | n/a | sim | não | **App** | **V11** |
| Organization preview | Worker | E6b | sim | plano | **sim** | catalogado | árvore | n/a | **sim** | **sim, na tela** | App | V6, V17, V19 |
| Organization execute | Worker | E1 | sim | linha + `movement` | — | catalogado | duas pontas | **sim** | sim | não | Worker | — |
| Undo | Worker | E1 | sim | linha + `movement` | — | movimento existe | por movimento | **sim** | sim | não | Worker | — |
| Conversão | Worker | E1+E3 | sim | `conversion_item_result` | — | ativo, vídeo, não-HEVC | por arquivo + quarentena | **sim** | sim | não | Worker | — |
| Duplicado exato — listar | App (leitura) | E6a | não | — | — | tem `sha256` | — | n/a | **sim** | sim | App | — |
| Dedup delete | Worker | E1 | sim | `movement` | — | catalogado, ativo | arquivo + quarentena | **sim** | sim | não | Worker | — |
| **PHOTO similarity** | Worker | E6b | sim | `similarity_grouping` | não¹ | **tem pHash** | nenhum | n/a | **sim** | **não** | App ×2 motores | V4, V8, V9, V15, V16, **V20** |
| **VIDEO similarity** | Worker | E6b | sim | idem | não¹ | **tem frames** | nenhum | n/a | **sim** | **não** | App ×2 motores | idem |
| Quarentena — restore lote | Worker | E1 | sim | por item | — | em quarentena | duas pontas | **sim** | sim | não | Worker | — |
| **Quarentena — restore unitário** | Worker | E1 | sim | por item | — | idem | duas pontas | **sim** | sim | **sim** | **App** | V3 |
| Quarentena — purga | Worker | E1 | sim | linha | — | em quarentena | arquivo | **sim** | sim | não | Worker | — |
| **Quarentena — cleanup ausentes** | Worker | E2 | sim | linha | — | movimento existe | arquivo | não | sim | sim | **App** | — |
| **Explorer — rename** | Worker | E1 | sim | linha + catálogo | — | existe no disco | duas pontas | **sim** | sim | **sim** | **App** | V3, V12 |
| **Explorer — quarentenar** | Worker | E1 | sim | `movement` | — | catalogado | árvore + quarentena | **sim** | sim | sim | **App** | V3 |
| **Explorer — apagar** | Worker | E1 | sim | linha | — | existe no disco | árvore | **sim** | sim | sim | **App** | **V2** |
| Metadata rebuild | Worker | E2 | sim | linha | — | catalogado | escopo pedido | não | **sim²** | não | App | — |
| Geolocation rebuild | Worker | E2 | sim | linha | — | tem coordenadas | escopo pedido | não | **sim²** | não | App | — |
| Dataset geográfico | Worker | E2+E3 | sim | linha | — | nenhuma | nenhum | não | sim | não | App | — |
| **Catalog purge** | Worker | E2 | sim | linha | — | `MISSING` há N dias | nenhum | não | sim | não | **App** | **V5** |
| Library switch | App + comando | E9 | parte | linha | — | pasta válida | **global** | n/a | **não** | sim | App | decomposição |
| Backup / restore | App | E8 | não³ | — | — | nenhuma | **global** | n/a | **não** | sim | App | escopo do guarda |
| Miniatura sob demanda | App | E3 | não | — | — | arquivo legível | nenhum | n/a | sim | sim | App | — |
| Update / ferramentas | App | E8 | não | — | — | nenhuma | nenhum | n/a | sim | sim | App | — |

¹ o agrupamento **não** é efêmero: vale até ser substituído (VIII.106).
² hoje **bloqueado** por inventário ativo — bloqueio a rever (VIII.104); o escopo correto é por
arquivo/pasta.
³ merece registro de auditoria; ver decisão aberta.

**Leitura da coluna "c/ Inv.": todas as capabilities podem coexistir com inventário, exceto duas** —
troca de biblioteca e restore de backup, que mudam a premissa global. Hoje **sete** telas bloqueiam
por inventário; **cinco** desses bloqueios não se sustentam.

## VIII.110 App × Worker Architecture — Consolidated Contract

Vinte respostas, para não ser preciso reler a discovery.

**1. O que é a App?** A fronteira com o usuário e com as intenções externas: interação, validação
prévia, transformação de intenção em trabalho durável, consulta e apresentação, e o ciclo de vida da
instalação.

**2. O que é o Worker?** O executor. Recebe comandos completos e os realiza sob disciplina,
reportando por estado durável.

**3. Quem cria trabalho?** A App — sempre. Schedulers e watchers são produtores de intenção, não
executores.

**4. Quem executa?** O Worker, exclusivamente, para todo efeito sobre a biblioteca (E1), sobre o
catálogo da coleção (E2) e para toda leitura que produza artefato consumido depois (E6b).

**5. Qual é o único caminho de execução?**
`intenção → Execution PENDING → claim → dispatcher → handler → estado/resultado duráveis`.
A App **MUST NOT** chamar handler, executar `Execution` que criou, criar atalho para operação pequena
ou tratar Worker indisponível como autorização para executar.

**6. Como a App acorda o Worker?** `NOTIFY` na **mesma transação** do `INSERT`. O sinal é otimização;
o polling adaptativo é fallback; o polling periódico é a rede final.

**7. Qual é a fonte de verdade?** PostgreSQL: a linha `execution` e os resultados duráveis. Sinal,
evento e cache **MUST NOT** ser fonte de verdade.

**8. Quando a App pode esperar?** Quando a UX daquela capability justificar — é decisão **por
capability**, não obrigação. Espera com orçamento curto, sem bloquear a thread do contêiner.

**9. Esperar significa executar?** **Não.** Esperar é apresentação.

**10. O que acontece se o Worker estiver fora?** A App **aceita e enfileira**; a UI informa que o
processamento está parado e por quê. **MUST NOT** haver fallback de execução. Rejeição imediata só
para comando inválido em si.

**11. Como resultados atravessam processos?** Sempre por estado durável escrito pelo Worker e lido
pela App. Nunca por memória, canal efêmero ou objeto compartilhado.

**12. O que é resultado durável?** Aquele que sobrevive ao fim da execução que o produziu e é
consultável: contadores na linha, resultado por item em tabela própria, artefato de leitura em tabela
própria.

**13. O que é resultado efêmero?** Aquele que precisa ser durável para atravessar a fronteira, mas
não para ser guardado: o plano do preview. Tem **TTL próprio, menor que o da execução**.

**14. Como a similaridade funciona?** O usuário pede; o Worker calcula sobre o **conjunto elegível**;
o resultado é publicado inteiro (`BUILDING → ACTIVE`); a tela mostra **apenas `ACTIVE`**. A App
**não** espera. Um resultado publicado continua apresentável mesmo depois de entrarem arquivos novos;
a tela pode oferecer recalcular.

**15. Como o preview funciona?** O usuário pede; a App enfileira; o Worker produz e publica
(`BUILDING → READY`); a tela mostra quando ficar pronto, paginando por consulta. O `execute`
**recalcula** e **MUST NOT** depender do preview.

**16. Como readiness funciona?** **Por arquivo e por capability**, derivada de fatos existentes
(existe fingerprint? existe sha? é vídeo?). Uma capability trabalha sobre o conjunto elegível no
instante em que começa. **MUST NOT** existir um `ready` global.

**17. Inventário bloqueia globalmente?** **MUST NOT.** Trabalho pendente sobre X não impede operação
independente sobre Y. Os bloqueios atuais por "inventário ativo" são dívida (V20/V21).

**18. Quando exclusão global é permitida?** Só quando a invariante exige estado global estável: troca
de biblioteca e restore de backup. A primitiva é a janela de manutenção (advisory lock), que já
coordena os dois processos e morre sozinha se quem a segura cair.

**19. Como o combined deve se comportar?** **Idêntico.** Mesma fila, mesmo claim, mesmo lock, mesmo
lease. **MUST NOT** existir atalho condicionado ao perfil. Verificável: ao fim de uma operação,
`claimed_by` preenchido e `claim_count ≥ 1`.

**20. Como as mutações de biblioteca são restringidas estruturalmente?** Por **port**: só a
implementação declarada toca no filesystem da biblioteca, e só classes alcançáveis a partir de um
handler podem injetá-la. Capacidade concedida por injeção, não declarada por anotação.

## VIII.111 Novos defeitos

| # | Defeito | Evidência | Severidade | Slice sugerido |
| --- | --- | --- | --- | --- |
| **V20** | A tela de duplicados devolve `Page.empty()` enquanto qualquer inventário estiver ativo — leitura de resultado já calculado, indisponibilizada por trabalho não relacionado | `DuplicatesWebController:188` | **alta** | 4.2.1 |
| **V21** | Guardas por "inventário ativo" usam `executionQueryService.active()`, que devolve **a execução mais recente**; com o Worker executando em paralelo, um inventário em curso deixa de ser detectado se outra execução começou depois | `InventoryRunningState.isRunning()` | média | 4.2.1 |
| **V22** | O guarda do restore de backup pergunta por **inventário**, mas o que o restore não tolera é **qualquer** execução: ele derruba o banco sob os pés de todas. Deveria usar a janela de manutenção | `SettingsBackupWebController:58` | média | 4.2.1 |

V20–V22 somam-se a V1–V19. **Nenhum corrigido neste slice.**

## VIII.112 Decomposição definitiva da Fase 4.2

Refeita pela ordem real de dependências, com a informação nova. Cada slice deixa o sistema coerente e
nenhum cria segundo motor temporário.

| Slice | Conteúdo | Por que aqui |
| --- | --- | --- |
| **4.2.1 — Desbloqueio e defeitos ativos** | V1, V11, V20, V21, V22, V10, V17. Remover os bloqueios globais injustificados; corrigir os guardas restantes para o escopo certo | **nada depende de nada**; V1 e V11 bloqueiam qualquer migração, e V20 é perda de capacidade em uso normal. Entrega valor imediato ao usuário sem tocar na fronteira |
| **4.2.2 — Protocolo e saúde** | wake-up (`NOTIFY` + polling adaptativo), bounded wait opcional, `worker_instance` (V14) | pré-requisito de **toda** migração cuja UX espera; a saúde é pré-requisito da UX de "processamento parado" |
| **4.2.3 — Ports e enforcement** | `LibraryFileMutations`, `CatalogMutations`, regras P1–P4 de ArchUnit | **antes** de migrar mutações, para que migrem já pelo caminho certo e não seja preciso refazer |
| **4.2.4 — Explorer** | rename de arquivo, rename de pasta, quarentenar, apagar; V2, V3, V12 | depende de 4.2.2 (espera) e 4.2.3 (port) |
| **4.2.5 — Quarentena e catálogo** | restore unitário, cleanup de ausentes, catalog purge; V5 | mesma dependência, menor risco; fecha os writers interativos |
| **4.2.6 — Similaridade durável** | `similarity_grouping` (M3), estados, chave revista; V4, V8, V9, V15, V16; RF-1 se aprovado | **independe do protocolo**, porque não espera. Antecipado em relação ao 4.1E por ser o que a Fase 5 precisa como referência |
| **4.2.7 — Preview durável** | `organization_plan` + itens, TTL, publicação; V6, V18, V19; RF-2 se aprovado | depende de 4.2.3 apenas para consistência de estilo; independente do resto |
| **4.2.8 — Library switch** | decomposição S2 | depende do protocolo, do port e de a limpeza de catálogo já ser comando |

**Mudanças em relação à proposta do 4.1E:**

1. **A similaridade subiu** (era o penúltimo, agora é 4.2.6): a UX esclarecida mostra que ela **não
   depende** do protocolo de espera, então não precisa esperar por ele. E é a referência que a Fase 5
   vai copiar.
2. **Um slice novo de desbloqueio abre a fase** (4.2.1), com V20/V21/V22: são perda de capacidade
   real, custam pouco e não dependem de nada.
3. **Preview e similaridade ficaram independentes entre si** — podem ser feitos em qualquer ordem, ou
   em paralelo.

## VIII.113 Impacto na Fase 5

Reavaliando os sete workloads com a taxonomia consolidada — e não pela régua "é pesado":

| Workload | Categoria | Continua na Fase 5? | Observação |
| --- | --- | --- | --- |
| Backlog de pHash | **E2** (grava fingerprints) | **sim** | é escrita de catálogo; vai ao Worker por responsabilidade. **V13** (executa no arranque da App) some junto |
| Backlog de fingerprint de vídeo | E2 | sim | idem |
| Rebuild de metadata | E2 | sim | e o bloqueio por inventário cai em 4.2.1 |
| Rebuild de localização | E2 | sim | idem |
| Dataset geográfico | E2+E3 | sim | decomposto: baixar+importar é comando; agendar é da App |
| **PHOTO similarity** | E6b | **antecipada para 4.2.6** | é a referência de resultado durável para as demais |
| **VIDEO similarity** | E6b | **antecipada para 4.2.6** | idem |
| **Organization preview** | E6b | **antecipado para 4.2.7** | não estava na Fase 5 original; entrou por classificação |

**Dois workloads saem da Fase 5 e um entra na 4.2.** A Fase 5 fica com **cinco** workloads, todos E2,
todos com o mesmo molde da Fase 4 (payload, launcher, handler, progresso na linha) e nenhum
dependendo de modelo novo — porque os dois que dependiam foram antecipados.

**A regra que substitui "é pesado → Worker":** vai ao Worker o que produz efeito E1/E2 ou artefato
E6b. Peso decide *isolamento*, não *responsabilidade*.

## VIII.114 A Fase 4.1 está fechada?

**Sim.** Confrontando com os critérios exigidos:

| Critério | Situação |
| --- | --- |
| B′ consolidado | VIII.110 |
| Contrato App × Worker | VIII.110, vinte respostas normativas |
| Separação arquitetura × produto | VIII.102, com reclassificação retroativa |
| RF-1/RF-2 corretamente classificados | **C — propostas**, com consumidores investigados |
| Readiness por capability | VIII.103 — **já existe, derivada de fatos** |
| Coexistência com inventário | VIII.105, e a dívida em V20–V22 |
| Modelo de similaridade | VIII.106, com ciclo de vida corrigido |
| Modelo e retenção do preview | VIII.107, efêmero com TTL próprio |
| Quiesce | 4.1D: já durável, mantido |
| Worker health | `worker_instance`, seis campos |
| Enforcement | VIII.108, port com regras P1–P4 |
| Matriz de capabilities | VIII.109 |
| Violações conhecidas | V1–V22 |
| Sequência da 4.2 | VIII.112 |

**O que fica aberto não impede o fechamento:** RF-1 e RF-2 são decisões de produto que afetam *o que
a API responde*, não a arquitetura — e a arquitetura está definida nos dois casos. Os valores de TTL,
orçamento de espera e campos de `worker_instance` são tuning.

## VIII.115 Decisões que dependem de você

| # | Pergunta | Bloqueia | Natureza |
| --- | --- | --- | --- |
| 1 | **RF-1** — a API de similaridade pode responder `202` quando não houver análise válida? *(a tela do Nimbus não usa esses endpoints; o impacto é sobre consumidores externos, se existirem)* | 4.2.6 | produto |
| 2 | **RF-2** — a API de preview pode devolver referência em vez do plano, e o export ler o plano persistido? | 4.2.7 | produto |
| 3 | **V22** — o restore de backup deve passar a bloquear por **qualquer** execução (janela de manutenção) em vez de só por inventário? É mais restritivo que hoje | 4.2.1 | produto |
| 4 | Os **cinco bloqueios injustificados por inventário** podem cair (duplicados, configuração, ferramentas, geodata, metadata), mantendo apenas escopo por arquivo/pasta onde houver conflito real? | 4.2.1 | produto |
| 5 | A decomposição da 4.2 em oito slices, com a similaridade antecipada, está correta? | 4.2 | processo |
| 6 | TTL default do preview: **12 h** é razoável, ou você prefere outro valor? | 4.2.7 | tuning |
| 7 | Autoriza iniciar a **4.2.1**? | — | processo |

---

# Fase 4.2.1 — Coexistência: remover a exclusão global indevida

Implementação dos defeitos V20, V21, V22, V1 e V11 identificados na 4.1. Este slice não migra
capability nenhuma para o Worker: ele corrige guardas que decidem *quando* algo pode acontecer, e que
foram escritos quando havia uma execução por vez no processo.

## VIII.116 O que foi corrigido, e o que a correção prova

| # | Defeito | Correção | Prova |
| --- | --- | --- | --- |
| **V21** | `InventoryRunningState` lia a execução ativa **mais recente** e comparava o tipo | pergunta ao repositório se existe alguma `INVENTORY` em status ativo | `ConcurrentActiveExecutionsIntegrationTest` |
| **V20** | a tela de duplicados devolvia `Page.empty()` durante qualquer inventário | a tela apresenta o que já foi analisado; o aviso permanece | `DuplicatesWebControllerTest.duplicatesShouldStayUsableDuringAnActiveInventory` |
| **V22** | o restore de backup bloqueava só por inventário | bloqueia por **qualquer** execução ativa | `SettingsBackupWebControllerTest`, dois casos |
| **V1** | `InventoryLauncherService.launch` varria execuções órfãs a cada chamada | a varredura fica só no arranque | dependência removida; `ExecutionQueueIntegrationTest.doesNotReportAnExecutionWhoseLeaseIsStillValidAsUnowned` |
| **V11** | o watcher executava `reconcileAndApply` na própria thread | enfileira `RECONCILE`, como o `ReconcileScheduler` já fazia | `InventoryWatchServiceTest`, dois casos novos |

## VIII.117 V21 — por que "a execução ativa" deixou de ser uma pergunta respondível

`ExecutionQueryService.active()` resolve para
`findFirstByFinishedAtIsNullAndStatusInOrderByStartedAtDesc` — **uma** linha, a que começou por
último. Comparar o tipo dessa linha com `INVENTORY` responde "há um inventário rodando?" apenas
enquanto existir no máximo uma execução ativa. A partir da Fase 4 o Worker executa várias, e o guarda
passou a responder conforme a ordem de início de execuções que nada têm a ver umas com as outras:

- inventário às 10h00, conversão às 10h05 → `active()` devolve a conversão → **o inventário some**;
- inventário às 10h00, nada mais → `active()` devolve o inventário → o inventário aparece.

O guarda falha **aberto** — deixa passar a ação que existia para impedir —, e falha de forma não
determinística. A correção pergunta o que a pergunta significa: *existe* alguma execução de tipo
`INVENTORY` em status não terminal.

O mesmo defeito estava em `FingerprintBacklogEngine.activeTypeIsOneOf`, que decide se o backlog de
fingerprints cede a vez a um inventário ou a uma conversão. Corrigido junto, com a mesma consulta.

**Consequência de contrato:** `InventoryRunningState` e o engine passam a depender de
`ExecutionRepository` em vez de `ExecutionQueryService`. É a camada certa: a pergunta é sobre
existência de linha, não sobre a execução que a tela está mostrando.

## VIII.118 V20 — a tela de duplicados durante o inventário

O bloqueio removido era este:

```java
if (inventoryActive) {
    addPageAttributes(model, Page.empty(), List.of());
    return "app/duplicates";
}
```

Uma leitura de resultado já calculado sendo negada por causa de escrita em curso sobre **outros**
arquivos. Numa biblioteca que recebe fotos por backup automático, o inventário roda quase sempre — e
"espere o inventário terminar" vira "não hoje". O aviso na tela permanece: a incompletude passa a ser
**declarada**, não imposta.

## VIII.119 Os sete bloqueios por inventário, um a um

A 4.1F estimou que cinco dos sete eram injustificados. **A investigação individual desmentiu a
estimativa**: só um era. Registrado aqui porque a diferença importa — remover os cinco
mecanicamente teria aberto três janelas de escrita concorrente sobre as mesmas colunas.

| Lugar | Veredito | Motivo |
| --- | --- | --- |
| `DuplicatesWebController` | **removido** | leitura; nada do que o inventário escreve invalida o que já foi analisado |
| `SettingsBackupWebController.restoreBackup` | **substituído** por "qualquer execução" | ver VIII.120 |
| `SettingsMetadataWebController` (rebuild) | **mantido** | escreve as mesmas colunas de metadata que o inventário está escrevendo |
| `SettingsGeodataWebController.rebuildLocations` | **mantido** | idem, para as colunas de localização |
| `SettingsGeodataWebController` — baixar/remover dataset | **mantido** | troca a base geográfica sob resoluções em voo |
| `SettingsGeodataWebController.clearGeoCache` | **mantido** | o próprio código já explica: limpar no meio desfaz trabalho em andamento |
| `SettingsToolsWebController.installTools` | **mantido** | o inventário usa ffprobe/exiftool enquanto o instalador os substitui |
| `SettingsParameterWebController.update` | **mantido, com ressalva** | bloqueia *toda* a tela de configuração, inclusive chaves que o inventário não lê |
| `GeoDatasetAutoUpdateScheduler` | **mantido** | é adiamento de tarefa automática, não recusa de ação do usuário |

**A ressalva de `SettingsParameterWebController` fica registrada como pendência de produto, não de
arquitetura.** Estreitar o bloqueio exige decidir *quais chaves* são sensíveis a um inventário em
curso — uma decisão de domínio. O guarda continua correto (protege demais, nunca de menos), e a
classificação é **C — regra/UX, para sua decisão**.

## VIII.120 V22 — a prova de que o restore exige zero execuções

O enunciado pedia para **não** trocar "bloqueia se INVENTORY" por "bloqueia se qualquer execução"
sem derivar a incompatibilidade real. A derivação:

1. `CatalogBackupService.restore` chama `catalogDump.restore(dump)`, que é um **`pg_restore` sobre o
   catálogo inteiro** — o arquivo carrega o schema, não só as linhas.
2. `pg_restore` derruba e recria **todas** as tabelas do backup. Entre elas está **`execution`**.
3. Toda execução, de qualquer tipo, tem uma linha em `execution`: é onde grava progresso, onde renova
   lease, e é o que a posse (`ExecutionOwnership`) verifica antes de cada checkpoint.

Logo **não existe execução independente do restore**. A independência que se poderia argumentar seria
sobre os *arquivos* — uma conversão mexendo em vídeos que o backup não descreve —, mas ela não
sobrevive ao passo 3: a conversão continuaria escrevendo progresso numa linha que o restore está
substituindo pela linha equivalente do backup. O resultado não é uma corrida sobre dados do usuário,
é uma execução cuja identidade é trocada no meio.

**"Nenhuma execução" é, portanto, consequência técnica e não simplificação.** O guarda foi
implementado assim, com a mensagem dizendo o motivo ao usuário.

**O que este slice deliberadamente não fez:** transformar o restore em drenagem coordenada — abrir a
janela de manutenção, cancelar o que está em curso e esperar. Isso muda a UX (o usuário passaria a
esperar em vez de ser recusado) e pertence ao slice que trata a troca de biblioteca, onde a mesma
primitiva é necessária. Hoje o restore **recusa e explica**, que é o comportamento que já existia,
apenas com o critério correto.

**Um efeito colateral favorável:** com V21 corrigido, o bloqueio anterior também teria voltado a
funcionar — mas continuaria errado, porque protegia só contra inventário. As duas correções são
independentes e ambas necessárias.

## VIII.121 V1 — a varredura de órfãs saiu do caminho de execução

`InventoryLauncherService.launch` chamava `executionProgressService.markInterruptedExecutions()`
antes de enfileirar. Herança do modelo anterior, em que a recuperação era preguiçosa — feita "logo
antes do próximo inventário" —, e que o `StartupExecutionRecoveryListener` já substituiu por uma
passagem única no arranque.

Por que continuar chamando é **errado sob Worker**, e não apenas redundante: a varredura marca
`INTERRUPTED` tudo que (a) não tem lease válido e (b) não está vivo *neste JVM*. A App não consegue
responder (b) sobre uma execução do Worker. Um lease que atrase a renovação — máquina sob carga,
pausa de GC — faz um clique em *Inventariar* declarar interrompida uma execução que o Worker segue
executando. A App decidiria sobre trabalho alheio a partir de uma pergunta que só sabe responder
sobre si mesma.

Removida a chamada, a dependência `ExecutionProgressService` ficou morta no launcher e saiu junto.

**Quem continua cuidando disso:** o arranque (`StartupExecutionRecoveryListener`, perfil da App) e o
`ExecutionReclaim` do Worker, que pergunta por *leases expiradas* — pergunta que atravessa processos,
ao contrário de "está vivo na minha memória".

## VIII.122 V11 — o watcher enfileira em vez de executar

`InventoryWatchService.automaticReconcile` chamava `organizationReconcileService.reconcileAndApply`
na thread do watcher. Passou a enfileirar uma execução `RECONCILE`, exatamente como o
`ReconcileScheduler` já fazia desde a Fase 4 — mesmo tipo, mesma `dedupKey`, mudando só o gatilho
(`FILE_EVENT` em vez de `TIMER`).

**O que foi preservado:**

| Preservado | Como |
| --- | --- |
| debounce por tempo | intacto — `DEBOUNCE_MILLIS` e `lastEventMillis` continuam decidindo *quando* pedir |
| coalescing | agora em **duas** camadas: o debounce em memória e a deduplicação por `dedupKey` no `enqueue`, que recusa um segundo `RECONCILE` para a mesma pasta |
| `lastReconciliation` / `lastReconciliationRepaired` no `layout.html` | passaram a ser **lidos do banco** — o último `RECONCILE` `FINISHED`, com `finishedAt` e `repairedItems` |

**O coalescing melhorou, não piorou:** o debounce anterior vivia só na memória do processo. Agora uma
rajada que chegue durante a passagem encontra o pedido já na fila, e um reinício não perde o pedido.

**Mudança de significado, declarada:** `lastReconciliation` passou a refletir a última reconciliação
concluída **de qualquer origem** (evento de arquivo ou agendador), e não apenas as que este watcher
disparou. O rótulo da tela — "Última reconciliação" — passou a ser literalmente verdadeiro; antes
omitia as passagens do agendador. É ampliação de veracidade, não mudança de regra, e não altera o que
o usuário faz com a informação.

**Código removido por ter ficado órfão:** `ReconcileExecutionRecorder` (e seu teste). Ele existia
para *criar* uma linha de execução depois do fato, quando o reconcile acontecia fora da fila. Com o
reconcile sendo uma execução desde o início, gravar uma execução para descrevê-la seria gravar duas.

## VIII.123 Divergências entre a modelagem 4.1 e o código real

Registradas como a modelagem pediu:

| Onde | A 4.1 dizia | O código mostrou |
| --- | --- | --- |
| VIII.104 | "cinco dos sete bloqueios são injustificados" | **um** era; três protegem escrita concorrente sobre as mesmas colunas, dois protegem recursos que o inventário está usando |
| VIII.104 | o restore "deveria usar a janela de manutenção" | a janela é a primitiva certa para **drenar**; para **recusar**, a pergunta correta é a existência de execução ativa — e a razão é a recriação da tabela `execution`, que a 4.1 não havia identificado |
| V11 (4.1D) | a preocupação era o custo da passagem na thread do watcher | o custo é real, mas o motivo mais forte é outro: o reconcile é uma capability de escrita no catálogo, e a App deixou de ser executor |
| V1 | descrito como "chamada redundante" | é mais que redundante: sob Worker ela pode interromper execução viva de outro processo |

## VIII.124 Defeitos novos encontrados durante a 4.2.1

Nenhum defeito funcional novo. Duas observações de dívida, sem correção neste slice:

- **`SettingsParameterWebController.update` bloqueia toda a tela de configuração** durante um
  inventário (VIII.119). Registrado como **C**.
- **`ExecutionQueryService.active()` continua sendo a pergunta certa para a tela** (qual execução
  mostrar) e a pergunta errada para guardas (existe execução de tipo X). Os dois usos passaram a
  estar separados; qualquer guarda novo que chame `active()` está repetindo V21.

## VIII.125 O que 4.2.1 não fez

- não migrou Explorer, quarentena, preview nem similaridade;
- não implementou `LISTEN/NOTIFY` nem espera limitada;
- não mudou nenhuma API para `202`;
- não estreitou o bloqueio da tela de configuração (depende de decisão de produto);
- não transformou o restore em drenagem coordenada.

## VIII.126 Proposta detalhada da Fase 4.2.2 — Protocolo e saúde do Worker

Escrita aqui como **proposta**, não como decisão: nada dela foi implementado na 4.2.1.

### O problema que ela resolve

Hoje o Worker descobre trabalho **apenas por polling**. O intervalo é o que separa "o usuário
clicou" de "algo começou a acontecer" — e é o mesmo intervalo para uma organização de duas horas e
para um rename de um arquivo. Enquanto todas as capabilities migradas eram de lote, isso não
incomodou. As três próximas (Explorer, quarentena unitária, preview) são interativas: o usuário
clica e olha para a tela.

Além disso, quando **nada acontece**, a App não sabe dizer por quê. Um Worker morto, um Worker que
não consegue alcançar o banco e um Worker ocupado com outra coisa produzem a mesma tela: uma execução
`PENDING` parada. Não há como a App dizer "o processamento está parado" porque ela não tem o fato.

### Peça 1 — `NOTIFY` na mesma transação do `INSERT`

`ExecutionEnqueueService.enqueue` emite `NOTIFY nimbus_execution` **dentro da transação que insere a
linha**. O Postgres só entrega a notificação no commit, o que dá exatamente a garantia que se quer:
não existe notificação de trabalho que não esteja visível para o `SELECT ... FOR UPDATE SKIP LOCKED`
do reserve.

No Worker, uma conexão dedicada em `LISTEN`, fora do pool — o `LISTEN` prende a sessão, e uma
conexão emprestada do Hikari e devolvida perderia a inscrição.

**O sinal é otimização, e o desenho tem de assumir que ele se perde.** Notificação não sobrevive a
uma reconexão, não é entregue a quem não estava escutando, e não tem confirmação.

### Peça 2 — polling adaptativo

O polling continua existindo e passa a ter dois regimes:

| Regime | Quando | Intervalo |
| --- | --- | --- |
| ativo | acabou de processar algo, ou chegou notificação | curto |
| ocioso | N ciclos sem trabalho | cresce até um teto |

O sinal apenas **encurta a espera do próximo ciclo**; nunca é a única razão de acordar. Um Worker
que perdeu todas as notificações continua drenando a fila — mais devagar, e sem intervenção.

**Ponto de atenção que a 4.1 não resolveu:** `LISTEN` numa conexão fora do pool precisa de política
de reconexão própria, e a reconexão tem uma janela cega. A rede final é o polling; o teste que
importa é derrubar a conexão de `LISTEN` e verificar que o trabalho enfileirado nesse intervalo
ainda é processado.

### Peça 3 — espera limitada, por capability

Uma capability interativa pode **esperar até um orçamento** pelo resultado, em vez de responder
"pedido aceito". A espera é do lado da App: enfileira, aguarda a execução terminar até *T*, e:

- terminou dentro de *T* → responde o resultado, e a UX é a de hoje;
- não terminou → responde a referência, e a tela passa a acompanhar pela execução.

**O orçamento é tuning, não arquitetura**, e é **por capability** — a similaridade não espera
(decidido em 4.1F), o rename provavelmente espera.

**Restrição que o desenho precisa respeitar:** esperar segurando uma thread de request é aceitável
para um orçamento de segundos e inaceitável como padrão geral. O que decide é a UX daquela ação, não
a conveniência da implementação.

### Peça 4 — `worker_instance` (V14)

Uma tabela com uma linha por processo Worker vivo, com heartbeat. É o que permite a três telas
diferentes dizerem a verdade:

| Pergunta da tela | Hoje | Com a tabela |
| --- | --- | --- |
| "por que nada acontece?" | sem resposta | "nenhum worker está vivo" vs "há um worker, ocupado" |
| "posso fechar o app?" | sem resposta | há trabalho em curso e quem o executa |
| "o processamento parou?" | inferido do tempo | heartbeat vencido é fato, não suspeita |

O heartbeat é **do processo**, distinto do lease, que é **da execução**. Um Worker pode estar vivo
sem segurar execução nenhuma, e uma execução pode ter lease válido por alguns segundos depois que o
processo morreu.

### Sequência sugerida dentro da 4.2.2

1. `worker_instance` + heartbeat + a leitura na tela (entrega valor sozinha, sem tocar no protocolo);
2. polling adaptativo (reduz latência sem depender de `NOTIFY`);
3. `LISTEN/NOTIFY` (otimização por cima, com o fallback já provado);
4. espera limitada, com **uma** capability piloto — a que a 4.2.4 for migrar primeiro.

Nessa ordem cada passo é reversível e nenhum depende do seguinte para deixar o sistema coerente.

### O que precisa da sua decisão antes de começar

| # | Pergunta |
| --- | --- |
| 1 | O orçamento de espera piloto: quanto tempo é aceitável a tela ficar "pensando" num rename? |
| 2 | Quando não houver Worker vivo, a App deve **recusar** o pedido com explicação, ou **aceitar** e mostrar "aguardando processamento"? |
| 3 | O heartbeat vencido deve aparecer como aviso na interface, ou só na tela de execuções? |

## VIII.127 Medição da 4.2.1

Build limpo, PostgreSQL real, um único Maven na máquina:

```text
Tests:       3017 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98,39% instrução, 92,32% branch, 97,85% linha, 98,85% método, 100,00% classe
SpotBugs:    -Pspotbugs verify verde, BugInstance size is 0, nenhuma exclusão nova
Sonar:       0 issues abertas
```

**A cobertura ficou onde estava.** Contra o registro anterior (98,39 / 92,33 / 97,85 / 98,85), só o
branch difere — em **uma unidade de 5.849**, dentro da variação entre execuções que o `AGENTS.md`
descreve. Verificado no relatório do JaCoCo, classe a classe: **nenhum método, branch ou linha do
código desta fatia ficou descoberto**. Os dois pontos que ficaram foram cobertos por teste honesto
enquanto o slice corria — a leitura da última reconciliação, nos dois sentidos (existe uma, e ainda
não existe nenhuma).

O piso continua onde estava, e continua não alcançado pelos mesmos 67 instruções, 7 branches, 24
linhas e 3 métodos de resíduo já declarados em VII.8. Esta fatia não o moveu em nenhuma direção.

**Seis issues do Sonar surgiram e foram eliminadas antes do fechamento**, todas em código de teste
introduzido aqui: cinco `java:S1144` (helpers privados que ficaram sem chamador quando os stubs
mudaram de forma) e uma `java:S8694` (literal `8` onde cabia `Month.AUGUST`). O total voltou a zero.


---

# Fase 4.2.2 — Protocolo App → Worker e saúde do executor

## VIII.128 Reconciliação de escopo (feita antes de qualquer alteração de código)

Regra nova, válida daqui em diante: **todo slice começa confrontando seu enunciado com o backlog
vigente da Parte VIII**, e só prossegue se não houver divergência material. Ela nasce do ocorrido na
4.2.1, onde V10 e V17 constavam do slice em VIII.112, não constavam do enunciado, e não foram
mencionados no fechamento — o que fez parecerem esquecidos.

### 1. O que o documento atribui à 4.2.2

Por VIII.112: wake-up (`NOTIFY` + polling adaptativo), *bounded wait* opcional, `worker_instance`
(V14). Por VIII.86, os mesmos três aparecem como **BLOCKER antes de qualquer migração interativa**.

### 2. O que o enunciado atribui à 4.2.2

V10 (abertura, por retificação); wake-up por `NOTIFY`; polling adaptativo; primitiva de espera
limitada, possivelmente só a camada inferior; saúde durável do Worker (V14); os fatos que permitem
distinguir indisponibilidade; e um conjunto de invariantes e provas — separação
heartbeat/lease/advisory lock, combined sem atalho, multi-worker, ordenação transacional, falhas
A–L.

### 3. Item do documento ausente do enunciado

**Nenhum.** Os três itens de VIII.112 estão todos no enunciado.

### 4. Item do enunciado atribuído pelo documento a outro slice

**V10**, que VIII.112 punha na 4.2.1 e VIII.86 classificava como "qualquer momento". É exatamente a
retificação aprovada, registrada em VIII.129 — não é divergência pendente.

Os demais parágrafos do enunciado (§6, §7, §9–§12, §14) não são itens de backlog: são invariantes e
critérios de prova sobre os três itens acima. Não deslocam escopo.

### 5. Decisões posteriores que tornaram a decomposição antiga inconsistente

Quatro, nenhuma bloqueante — todas já resolvidas no sentido em que o enunciado aponta:

| Decisão posterior | O que desatualizou | Como fica |
| --- | --- | --- |
| **4.1F / VIII.102** rebaixou o *bounded wait* de obrigação a escolha por capability | VIII.86 ainda o lista como "BLOCKER antes de qualquer migração interativa", sem qualificação | continua blocker **para as capabilities que decidirem esperar**; para as que não esperam (similaridade), não é pré-requisito de nada. O enunciado já usa a formulação nova |
| **4.2.1 / V11** fez o watcher enfileirar `RECONCILE` | quando VIII.112 foi escrita, só o agendador enfileirava reconcile | a taxa de `enqueue` subiu: agora toda rajada de eventos de arquivo produz um pedido. Coalescing e wake-up espúrio deixaram de ser hipótese e passaram a ser o caso normal — informação que **muda o desenho do polling adaptativo**, não o escopo |
| **4.2.1 / V21** mostrou que `ExecutionQueryService.active()` responde "a execução mais recente", não "existe execução tal" | a semântica de indisponibilidade (§7) precisa de perguntas por existência | restrição de desenho registrada: nenhum fato novo deste slice pode ser derivado de `active()` |
| **V13** continua aberto: `PhashBacklogStartup` e `VideoFingerprintBacklogStartup` executam backlog no `ApplicationReadyEvent` **da App** | §14 enuncia "APP MUST NOT executar handler" como invariante | o invariante descreve o **alvo**; hoje o backlog de fingerprints ainda roda na App, e VIII.86 o atribui à convergência da Fase 5. Este slice **não** fecha esse buraco, e não deve ser lido como se fechasse |

### 6. Perguntas de VIII.126 que deixaram de bloquear

A proposta que fechou a 4.2.1 terminava com três perguntas — orçamento de espera piloto, o que fazer
sem Worker vivo, e onde mostrar heartbeat vencido. **As três eram de UX, e o enunciado tirou UX do
escopo** (§4: sem integração automática; §7: sem texto, status HTTP ou UX global). Ficam adiadas para
os slices consumidores, sem bloquear este.

### Veredito

**Sem divergência material.** O slice prossegue com: V10 na abertura, wake-up, polling adaptativo,
saúde durável, e a primitiva de espera na medida em que tiver consumidor real.

## VIII.129 Retificação da 4.2.1 e do backlog

Aprovada explicitamente pelo dono do projeto. **VIII.112 permanece como foi escrita** — o histórico
não se apaga — e esta seção passa a prevalecer sobre ela onde divergirem:

| Item | Onde estava | Onde fica | Razão |
| --- | --- | --- | --- |
| 4.2.1 | V1, V11, V20, V21, V22, **V10, V17** | V1, V11, V20, V21, V22 | é o que o enunciado da 4.2.1 delimitou e o que foi entregue |
| **V10** | 4.2.1 | **abertura da 4.2.2** | sem dependência ("qualquer momento" em VIII.86); é o escape hatch que o slice do protocolo deve fechar antes de construir o protocolo |
| **V17** | 4.2.1 | **4.2.7** | VIII.86 já o classificava como *BLOCKER antes do Preview*, e VIII.112 o contradizia. A classificação por precedência prevalece: corrigir a gravação enquanto o `OrganizationPlanStore` ainda é memória, e prestes a virar tabela, seria mexer duas vezes na mesma linha |

**Razão histórica da divergência.** VIII.112 foi escrita para agrupar por *custo de execução* — V10 e
V17 são pequenos, e couberam no slice de abertura. VIII.86 fora escrita antes, agrupando por
*precedência técnica*, e nela V17 já era bloqueador do Preview. As duas tabelas nunca foram
conciliadas entre si. O enunciado da 4.2.1 seguiu a precedência sem dizê-lo, e o fechamento não
apontou a diferença — falha de relatório, não de execução. A regra de reconciliação em VIII.128
existe para que isso não se repita.


## VIII.130 V10 fechado, e a varredura por portas equivalentes

`ConversionExecutionRecorder.start(Path, int)` construía e persistia uma `Execution` `CONVERSION`
já em `RUNNING`, fora de `enqueue → PENDING → claim → lease → dispatcher → handler`. Não tinha
chamador de produção desde que o launcher passou a criar a linha; o que restava era a forma de uma
API pronta para violar B′ sem que ninguém precisasse decidir violá-la.

Removidos: o método, os dois testes que só o exercitavam
(`opensAConversionExecutionForTheFolderTheBatchRunsIn`, `acceptsABatchWithNoFolderToRecord`) e o
`verify(..., never()).start(...)` de `VideoConversionServiceTest`, que afirmava que o caminho real
não o chamava — uma afirmação sem objeto depois da remoção. Os demais métodos do recorder
(`recordItem`, `recordFailure`, `fail`, `finish`) têm uso legítimo e ficaram.

**A prova é estrutural:** o método não existe, então nenhum código pode chamá-lo e o build recusa
qualquer tentativa de trazê-lo de volta por engano. Não há teste comportamental melhor do que isso —
um teste que afirmasse "ninguém chama" só teria valor enquanto o método existisse.

### Varredura por escape hatches equivalentes

Procurados: métodos que criem `Execution` diretamente em `RUNNING`, que persistam execução já
iniciada, ou que permitam pular `PENDING`/claim/dispatcher. Três achados, **nenhum deles um escape
hatch**:

| Local | Chamador real | O que é | Slice |
| --- | --- | --- | --- |
| `ExplorerDeletionService.startExecution` | `ExplorerDeletionService:140` | quarentenar pelo Explorer, capability ainda na App | 4.2.4 (V2/V3/V12) |
| `OrganizationService.startPreviewExecution` | `OrganizationService:81` | o preview, capability ainda na App | 4.2.7 |
| `QuarantineOperationLog.startRestore` / `startAbsentCleanup` | `QuarantineService:104`, `QuarantinePurgeService:271` | restore unitário e cleanup de ausentes, que a 4E decidiu **manter síncronos na App** | 4.2.5 |

A diferença com V10 é material: aquele era uma porta sem ninguém atrás; estes são os pontos de
entrada de capabilities que a convergência ainda vai migrar, todos já catalogados. Removê-los agora
**seria** migrar as capabilities, que é o que este slice não faz. **Escopo não expandido.**

## VIII.131 O protocolo: o que foi construído

### A invariante, e onde ela é imposta

> A linha durável é o comando. O sinal só diz "vá olhar".

Imposta em dois lugares concretos, não por convenção:

1. **O canal não tem payload.** `pg_notify(:channel, '')` — não existe o que serializar, então não
   existe a tentação de fazer do sinal um transporte. Um worker que recebe a notificação não sabe
   nada além de "algo pode ter mudado", e a única coisa que pode fazer com ela é consultar a fila.
2. **A perda do sinal não muda o resultado, só o atraso.** O loop continua fazendo exatamente a
   pergunta que fazia antes; o que mudou é quando ele a faz.

### App → Worker: `pg_notify` na transação do `INSERT`

`ExecutionEnqueueService.enqueue` passou a escrever a linha e sinalizar **dentro de uma transação**,
nessa ordem, via `ExecutionQueueNotifier`.

A ordenação exigida pelo §11 sai de graça, e não de disciplina: o PostgreSQL **retém a notificação
até o commit e a descarta no rollback**. Disso decorrem as duas direções:

- **não existe** notificação entregue cuja linha ainda não esteja visível — a janela que o enunciado
  pedia para não desenhar simplesmente não pode ocorrer;
- **não existe** notificação entregue por um pedido recusado — o duplicado que viola o índice parcial
  reverte a transação e leva o sinal junto.

A transação é explícita (`TransactionTemplate`) e não declarativa por um motivo: a violação do índice
precisa ser capturada **fora** dela. Dentro de um método `@Transactional`, capturar a
`DataIntegrityViolationException` deixaria a transação marcada para rollback e o commit falharia com
outra exceção — trocaríamos "já enfileirado" por um erro. Nenhum chamador de `enqueue` é
transacional (verificado nos nove call sites), então a transação criada aqui é sempre a mais externa.

### Worker: `LISTEN` em uma conexão, um contador para todos os loops

`ExecutionQueueSignals` (perfil `worker`) mantém **uma** thread e **uma** conexão em `LISTEN`.
`LISTEN` prende a inscrição à sessão, então uma conexão emprestada do pool e devolvida deixaria de
ouvir; esta é segurada enquanto o worker vive.

Os loops não tocam nessa conexão. Eles esperam num contador que a thread incrementa, o que dá três
propriedades de graça: uma notificação acorda os três loops; notificações repetidas coalescem
naturalmente (o contador anda uma vez por entrega, e o custo de um wake-up é uma consulta); e o
`WorkerLoop` não conhece nada de notificação além de "esperei e algo mudou, ou o tempo acabou".

**A ordem da leitura do contador é parte do desenho.** O loop lê `signalCount()` **antes** de
perguntar à fila e espera por um sinal *posterior* a esse. Lê-lo depois perderia exatamente a
notificação de um trabalho enfileirado enquanto a consulta estava em voo — e essa execução esperaria
o intervalo inteiro.

### Polling: continua sendo a rede, e não foi afrouxado

`WorkerLoop.sleepBeforeAskingAgain` virou `waitForWorkOrTimeout`, que espera **até** o mesmo
`pollSeconds` de antes. Um worker sem canal, com o canal caído, ou que perdeu a notificação
comporta-se exatamente como antes desta fase.

**Decisão consciente: o intervalo ocioso não foi aumentado.** Seria tentador — agora existe algo mais
rápido —, mas o intervalo é justamente o que cobre a falha do rápido, e alargar a rede confiando em
quem ela existe para socorrer é o erro clássico. Também **não** foi criada escada de backoff: com o
sinal cobrindo a latência, uma escada acrescentaria estado sem melhorar nada mensurável, e o teto de
cinco segundos ocioso já é uma consulta por loop indexada num conjunto de dezenas de linhas.

### Reconexão

Se a conexão cai, a thread faz backoff e reabre; nesse intervalo **o worker é apenas um worker que
pollea**. Ao reassinar, ela **sinaliza uma vez de propósito**: o que foi publicado enquanto ninguém
ouvia não foi entregue a ninguém, e olhar uma vez é mais barato do que raciocinar sobre o que se
perdeu.

### O que não foi feito, e por quê

**Worker → App continua sem notificação**, como a 4.1C decidiu e o §8 reafirma. A App observa o banco.
Nada aqui abriu conexão `LISTEN` na App, registrou futures em memória, nem criou callback do Worker
para a App.

**Nenhum servidor HTTP no Worker.** `web-application-type=none` intacto: o canal é o banco.

## VIII.132 Saúde do Worker (V14)

`worker_instance`, **duas colunas**: `worker_id` e `last_seen_at`.

A quantidade saiu de perguntas, não de estética. `started_at`, versão, hostname, pid e estado
operacional foram avaliados e **deixados de fora** por não terem consumidor: o supervisor já sabe
quando iniciou o filho, o `SchemaCompatibility` já recusa um worker de outro build, o Nimbus roda um
processo local, e "vivo mas em stand-down" é uma janela de milissegundos antes de o processo sair.
Coluna que ninguém lê é coluna que envelhece sem ninguém notar.

**Frescor não é armazenado.** Quem pergunta decide, comparando `last_seen_at` com
`WorkerHealthConstants.FRESH_WITHIN`. Um booleano gravado aqui seria uma decisão congelada no
instante da escrita, tomada pelo processo menos capaz de tomá-la.

**Duas instâncias continuam detectáveis:** uma linha por `worker_id`, e `worker_id` já carrega pid e
horário de início. `WorkerAvailabilityResponse.instances()` devolve a contagem em vez de um sim/não —
o Nimbus sobe um worker, então dois vivos é sintoma, e um booleano o esconderia.

**Retenção:** cada reinício gera um id novo, então um supervisor que reinicia o filho algumas vezes ao
dia deixaria uma tabela que só cresce. Quem chega limpa o rastro de quem não é visto há dois dias —
o processo que está entrando é o único certamente vivo naquele instante.

**Consumidor real:** `GET /api/worker`, que devolve `available`, `instances` e `lastSeenAt`. É fato,
não veredito: o que uma tela faz quando não há executor depende do que está na fila, e decidir aqui
imporia a mesma resposta a todas elas. `WorkerSupervisor.isRunning()` **não** foi removido — ele
responde outra pergunta (o filho *deste* processo está vivo?) e é o que a política de restart usa.

## VIII.133 Heartbeat, lease e janela de manutenção continuam três coisas

| Mecanismo | Pergunta | Escopo | Quem escreve |
| --- | --- | --- | --- |
| `worker_instance.last_seen_at` | há executor vivo? | o processo | `WorkerHeartbeat`, a cada 10 s |
| `execution.lease_until` | quem é o dono desta execução? | uma execução | `LeaseRenewer`, por execução possuída |
| advisory lock de manutenção | o sistema está parado para manutenção? | a instalação | quem abre a janela |

Nenhum foi reusado para o papel de outro. A diferença que mais importa: um worker **ocioso** não tem
lease nenhum para renovar, e é exatamente o worker cuja existência uma tela mais precisa conhecer.
Provado em `WorkerProtocolIntegrationTest.forgettingTheHeartbeatDoesNotDisturbAnExecutionInFlight`:
apagadas as linhas de presença, a execução mantém `claimed_by` e seu lease.

## VIII.134 Bounded wait: conscientemente adiado

**Não implementado neste slice, por decisão registrada — não por esquecimento.**

O que a 4.1C modelou e o §4 permite adiar: uma primitiva que observa o estado durável até um
orçamento, sem executar, sem claim, sem lease, sem cancelar nada.

Três razões para adiar:

1. **Não há consumidor.** O `AGENTS.md` proíbe código sem uso, e o enunciado proíbe framework
   especulativo. A primeira capability que vai querer esperar é o rename do Explorer, na 4.2.4 — e é
   lá que a forma certa (com ou sem MVC assíncrono, com que orçamento) fica decidível por evidência
   em vez de por suposição.
2. **Nada aqui bloqueia a construção dela depois.** O que uma espera precisa é observar estado
   durável, e isso é `ExecutionRepository` — que já existe e não mudou.
3. **A decisão de esperar é por capability** (VIII.102), então uma primitiva sem consumidor teria de
   adivinhar o orçamento, que é justamente o que se decidiu não generalizar.

Fica **aberto e atribuído à 4.2.4**, com o contrato já escrito em VIII.126.

## VIII.135 Combined: sem atalho, e a prova disso

O `app-worker-combined` ganha o canal como qualquer worker (`ExecutionQueueSignals` é do perfil
`worker`, ativo no combinado) e **nada mais**. Não existe caminho "já estamos na mesma JVM, chame o
handler": a App escreve `PENDING`, o lado worker é acordado, faz claim pelo `UPDATE ... FOR UPDATE
SKIP LOCKED` e despacha.

A prova já existia e continua valendo — `CombinedProfileIntegrationTest.runsWorkTheApplicationSideEnqueued`
afirma o resultado que só o worker escreve (`claimed_by` preenchido, `claim_count` = 1) a partir de
uma linha escrita como a App a escreve. É prova comportamental: nada ali chama o dispatcher.

## VIII.136 As falhas modeladas, e onde cada uma é provada

| | Situação | Como fica | Prova |
| --- | --- | --- | --- |
| A | linha persistida, `NOTIFY` recebido | worker acorda antes do poll | `aQueuedRequestReachesTheWorkerSoonerThanTheNextPoll` |
| B | linha persistida, `NOTIFY` perdido | polling encontra | `workNobodyAnnouncedIsStillFoundByPolling` |
| C | `NOTIFY` duplicado | uma execução, um claim | `repeatedAndEmptySignalsRunTheWorkOnceAndNothingTwice` |
| D | wake-up sem trabalho | custa uma consulta | idem |
| E | Worker inicia depois do `PENDING` | encontra na primeira rodada | `aWorkerFindsWorkThatWasQueuedBeforeItLooked` |
| F | conexão `LISTEN` cai e volta | backoff, reassina, sinaliza uma vez; no intervalo é um worker que pollea | código e VIII.131; sem teste automatizado (ver abaixo) |
| G | App cai depois do commit | a linha é o comando; nada mais era necessário | `workNobodyAnnouncedIsStillFoundByPolling` cobre o mesmo caminho |
| H | Worker cai antes do claim | a linha continua `PENDING` | `ExecutionQueueIntegrationTest` |
| I | Worker cai depois do claim | lease expira, `ExecutionReclaim` retoma — **mecanismo existente, não duplicado aqui** | `ExecutionReclaimTest`, `OwnershipLossIntegrationTest` |
| J | duas instâncias acordadas pelo mesmo sinal | wake-up ≠ posse; o claim atômico decide | `ExecutionQueueIntegrationTest.handsOneRowToExactlyOneOfTwoConcurrentClaimers` |
| K | janela de manutenção ativa | nada é despachado, sinal ou não | `CombinedProfileIntegrationTest.takesNothingWhileAnAdministrativeOperationHoldsTheBackgroundStill` |
| L | banco indisponível | sem canal e sem fila; o worker segue tentando e o heartbeat para de escrever, o que é justamente o que a App precisa ver | `WorkerHeartbeatTest.survivesARoundThatCouldNotReachTheDatabase` |

**F não tem teste automatizado, e isso é declarado.** Derrubar o socket de uma conexão específica do
pool no meio de um teste, sem derrubar o banco inteiro, exigiria proxy de rede (Toxiproxy) — uma
dependência nova de infraestrutura de teste para exercitar um caminho cuja correção não depende dele:
com o canal caído o sistema é o de antes desta fase, e é isso que B prova. Registrado como resíduo
consciente, não como cobertura esquecida.

## VIII.137 Divergências entre a modelagem 4.1 e a implementação real

| A 4.1 dizia | O código mostrou | O que prevaleceu |
| --- | --- | --- |
| VIII.126: "o intervalo é o que separa o clique de algo acontecer" | o intervalo real é **5 s** (`WorkerProperties.DEFAULT_POLL_SECONDS`), não algo maior | o problema é menor do que a proposta sugeria, mas **não desaparece**: 5 s é irrelevante para lote e é muito para um rename. O `NOTIFY` entra pelo caso interativo, e é registrado que ele **não** era necessário para as capabilities já migradas |
| VIII.126: "conexão dedicada fora do pool" | uma conexão **do** pool, segurada enquanto o worker vive | mesma propriedade (a sessão não é devolvida, então a inscrição não se perde) sem uma segunda configuração de conexão para manter em dia |
| 4.1D: "`worker_instance` seis campos" | quatro deles não têm leitor | **duas** colunas; a 4.1 já classificava a quantidade como tuning (VIII.102) |
| VIII.108 e a decisão do pom: "nenhuma classe de produção importa `org.postgresql`" | notificação assíncrona **não existe** na API JDBC | o driver volta a `compile` e **uma** classe o importa — um adapter em `infrastructure`, que é onde o `AGENTS.md` manda pôr dependência de tecnologia concreta. `application` e `domain` continuam sem citá-lo. A nota do `pom.xml` foi reescrita com o motivo novo em vez de apagada |
| 4.1C: "polling adaptativo" com escada de intervalos | nenhuma evidência de que 5 s ocioso custe algo | implementado como **sinal + intervalo fixo**, que é o que "adaptativo" precisava significar aqui: acordar na hora quando há trabalho, não consultar agressivamente quando não há |

## VIII.138 Defeitos novos encontrados

Nenhum defeito funcional novo. Uma observação registrada:

- **V13 continua aberto e este slice o torna mais visível.** `PhashBacklogStartup` e
  `VideoFingerprintBacklogStartup` executam backlog no `ApplicationReadyEvent` da App. Agora que a
  App sabe dizer se há Worker vivo, a assimetria fica evidente: existe trabalho que a App executa e
  para o qual a pergunta "há executor?" não se aplica, porque o executor é ela mesma. Continua
  atribuído à convergência da Fase 5 (VIII.86), e **§14 descreve o alvo, não o estado atual**.


## VIII.140 Proposta da Fase 4.2.3, confrontada com o código real

Proposta, não decisão. Escrita depois de 4.2.2 e conferida contra o que existe hoje, como manda a
regra de reconciliação (VIII.128).

### O que a Parte VIII atribui à 4.2.3

VIII.112: `LibraryFileMutations`, `CatalogMutations`, regras P1–P4 de ArchUnit, **antes** de migrar
mutações. VIII.108 detalha os ports e as quatro regras. VIII.86 põe "enforcement por port + ArchUnit"
como **após** a convergência das mutações.

### A contradição que o código expõe

**VIII.112 e VIII.86 discordam, e desta vez a discordância importa.**

A regra P2 diz: só classes alcançáveis a partir de um `ExecutionJobHandler` podem depender dos ports.
Mas hoje mutam arquivo da biblioteca, **na App**: o Explorer (`ExplorerRenameService`,
`DefaultExplorerFileSystem`, `ExplorerDeletionService`), a quarentena
(`QuarantinePurgeService` no restore unitário e no cleanup) e a limpeza de biblioteca
(`LibraryCatalogCleanupService`). Nenhuma dessas é alcançável a partir de um handler.

Então **P2 nasceria vermelha**. As saídas possíveis:

| Saída | Consequência |
| --- | --- |
| migrar Explorer e quarentena antes (4.2.4 e 4.2.5 primeiro) | inverte a ordem, e as capabilities migrariam sem o port — exatamente o "refazer depois" que VIII.112 quis evitar |
| criar os ports sem a regra, e a regra só depois | o port vira convenção: quem esquecer de usá-lo não é reprovado por nada |
| **criar os ports e as regras com uma lista explícita de exceções nomeadas** | a regra nasce verde e **encolhe** a cada slice; a lista é o placar da convergência |

**Proposta: a terceira.** É o que VIII.108 já previa em P4 ("a lista existe e cresce por decisão
consciente, revisada no próprio teste") — só que aplicada também a P2, e encolhendo em vez de
crescer. Cada entrada da lista carrega o slice que a remove.

### Peça 1 — os dois ports

`LibraryFileMutations` (mover com verificação, renomear, apagar arquivo, apagar diretório vazio) e
`CatalogMutations` (escritas em massa sobre o catálogo da coleção). O implementador é único por port
e é o *choke point*.

**Ajuste que o código real impõe:** o levantamento encontrou `Files.move`/`Files.delete` em **23
classes**, e a maioria não é biblioteca do usuário — miniaturas, temporários de conversão, download
de dataset geográfico, instalador de ferramentas, dump do banco, atualizador. A fronteira do port é
"arquivo do usuário", não "arquivo". O `AGENTS.md` já separa os dois casos na regra de *move seguro*,
e o port deve herdar essa fronteira em vez de inventar outra.

### Peça 2 — as regras, e o que cada uma custa hoje

| R | Regra | Estado hoje | Exceções iniciais |
| --- | --- | --- | --- |
| P1 | só a implementação declarada chama `Files.*` sobre caminhos da biblioteca | `SecureFileMove` já é quase isso | as classes de workspace/infra, por lista |
| P2 | só alcançáveis a partir de um handler dependem dos ports | **vermelha sem exceções** | Explorer (→4.2.4), quarentena (→4.2.5), `LibraryCatalogCleanupService` (→4.2.8) |
| P3 | nenhuma classe de `..web..`/`..rest..` alcança os ports | verde hoje | — |
| P4 | lista fechada de quem chama `Files.*` fora dos ports | verde por construção | é a própria lista |

### Peça 3 — a ferramenta

**ArchUnit não está no projeto** (nem dependência, nem teste). Entra como dependência de teste. Antes
de adotá-la, uma pergunta honesta: as regras P1–P4 são expressáveis com o que já existe? Parcialmente
— uma varredura de imports pega P3, mas "alcançável transitivamente a partir de um handler" é
análise de grafo, e escrevê-la à mão seria reimplementar ArchUnit pior. **Proposta: adotar ArchUnit,
com escopo restrito a estas regras.**

### O que a 4.2.2 mudou para a 4.2.3

1. **Há menos a migrar do que a decomposição supunha:** o reconcile reativo saiu da App na 4.2.1, e
   com ele um mutador de catálogo.
2. **A App já sabe se há executor** — o que a 4.2.4 precisa para decidir o que fazer quando não há.
3. **Nada em `application` ou `domain` importa `org.postgresql`**, e uma regra de ArchUnit barata pode
   travar isso, que hoje é só uma nota no `pom.xml`. Vale acrescentar como P5.

### Sequência sugerida

1. levantar e classificar as 23 classes que mutam arquivo (biblioteca × workspace × infraestrutura) —
   sozinho já é documento útil;
2. extrair `LibraryFileMutations` sobre `SecureFileMove`, sem mover ninguém para o Worker;
3. extrair `CatalogMutations`;
4. ArchUnit com P1–P5 e a lista de exceções datada por slice;
5. registrar no README que a lista é o placar da convergência.

### O que decidir antes

| # | Pergunta |
| --- | --- |
| 1 | Aceita a lista de exceções nomeadas, ou prefere inverter a ordem e migrar Explorer antes do enforcement? |
| 2 | ArchUnit entra como dependência de teste, ou prefere manter o projeto sem ela e aceitar cobertura parcial das regras? |
| 3 | P5 (proibir `org.postgresql` fora de `infrastructure`) entra junto? |

## VIII.139 Medição da 4.2.2

Build limpo, PostgreSQL real:

```text
Tests:       3033 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98,40% instrução, 92,36% branch, 97,85% linha, 98,86% método, 100,00% classe
SpotBugs:    -Pspotbugs verify verde, BugInstance size is 0, nenhuma exclusão nova
Sonar:       0 issues abertas
```

Contra a 4.2.1 (98,39 / 92,32 / 97,85 / 98,85): **subiu em três métricas e ficou igual na quarta**.
O piso continua onde estava, e continua não alcançado pelo mesmo resíduo declarado em VII.8 — esta
fatia não o moveu em nenhuma direção.

Uma issue nova apareceu no Sonar durante o trabalho e foi eliminada antes do fechamento:
`java:S3077` sobre `private volatile Thread`, corrigida com `AtomicReference` — que é como o resto do
projeto guarda referência mutável compartilhada.

### V23 — o fork da suíte passou a demorar a encerrar

Achado desta fatia, registrado como defeito próprio para não se perder:

**O sintoma.** Desde que o worker mantém uma conexão em `LISTEN`, a suíte termina com
`Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)`.
Não aparecia antes desta fase.

**O que foi investigado, e o que se aprendeu no caminho.** A primeira tentativa foi fechar a conexão
a partir do `@PreDestroy`, para desbloquear a leitura do socket. **Isso piorou tudo**: fechar uma
conexão emprestada do pool a partir de uma thread que não a pegou deixa o pool esperando por um
checkout que nunca volta, e a cobertura medida caiu junto - 98,33 / 97,71 em duas execuções seguidas,
contra 98,40 / 97,85 antes e depois. A correção foi devolver a conexão pelo caminho normal
(`try`-with-resources da própria thread) e encurtar o bloco para um segundo, o que restaurou a
medição exatamente.

**O que sobra.** O aviso permanece, e o build **não** ficou mais lento por causa dele — 5 min 06 s
contra 5 min 33 s da 4.2.1 —, a suíte passa e a cobertura é estável entre execuções. A causa
provável é o encerramento em cadeia dos vários contextos Spring em cache, cada um com seu pool, e não
uma thread pendurada: a do canal é daemon e sai em até um segundo.

**Severidade: baixa** — ruído no fim da suíte, sem efeito no resultado. Registrado porque a primeira
hipótese estava errada de um jeito que custou duas medições, e porque um aviso que ninguém explicou é
como um vermelho que se aprende a ignorar.


---

# Fase 4.2.3 — Ports de mutação e enforcement estrutural

## VIII.141 Reconciliação de escopo (antes de qualquer alteração de código)

### 1. O que VIII.140 atribui à 4.2.3

Os dois ports (`LibraryFileMutations`, `CatalogMutations`), as regras P1–P4, ArchUnit como
dependência de teste, P5 como sugestão, e a sequência: classificar as classes que mutam arquivo →
extrair os ports → ArchUnit com a lista de exceções datada por slice → registrar no README.

### 2. O que o enunciado atribui

Os mesmos itens, com P5 **aprovado** e três decisões fechadas (lista de exceções nominal, ArchUnit
aceito, P5 dentro). Acrescenta detalhamento que não desloca escopo: choke point único, preservação do
self-write, App × Worker, combined, testes semânticos, e a proibição explícita de migrar capability.

### 3. Item do documento ausente do enunciado

**Um, e é uma restrição, não uma omissão.** VIII.140 sugeria "registrar no README que a lista é o
placar da convergência"; o enunciado (§19) manda só atualizar o README se fizer sentido, e proíbe
seção criada apenas para anunciar ArchUnit. **Prevalece o enunciado**, que é mais recente e mais
restritivo.

### 4. Item do enunciado atribuído pelo documento a outro slice

Nenhum.

### 5. Decisões posteriores que desatualizaram regras

| Onde | O que envelheceu | Correção |
| --- | --- | --- |
| VIII.108 lista `ReconcileApplier` como consumidor de `CatalogMutations` a ser controlado | a 4.2.1 (V11) migrou o reconcile para o Worker | `ReconcileApplier` é hoje **consumidor legítimo**, alcançável a partir de `ReconcileJobHandler` — não é exceção |
| VIII.140 dizia que `LibraryCatalogCleanupService` muta arquivo da biblioteca e seria exceção de P2 | o código apaga o **cache de thumbnails do workspace**, não a biblioteca | não é exceção de `LibraryFileMutations`; é consumidor de `CatalogMutations` (apaga linhas de catálogo em massa) |
| VIII.140 falava em "23 classes com `Files.move/delete`" | o levantamento completo, com todos os mutadores, encontra **75 call sites em 29 classes** | a matriz de VIII.142 substitui a estimativa |
| VIII.108 previa `EmptyDirectoryCleaner` e `QuarantinePurgeService` como consumidores | continuam, e apareceram **dois consumidores que a modelagem não previa** | `ConversionCommitService` (carimbo de data no arquivo já colocado) e `CatalogBackupService` (grava o zip num destino que pode estar sob observação do watcher) |

**Sem divergência material.** O slice prossegue.

## VIII.142 Classificação de toda mutação de filesystem em produção

Levantados **75 call sites em 29 classes**, procurando `move`, `delete`, `deleteIfExists`, `copy`,
`write`, `writeString`, `newOutputStream`, `createFile`, `createDirectory`, `createDirectories`,
`createTempFile`, `createTempDirectory`, `setLastModifiedTime`, `setAttribute` e
`setPosixFilePermissions`. A classificação é pelo **significado do `Path`**, nunca pelo package.

### A — LIBRARY: arquivo do usuário, ou destino observável pelo watcher

| Classe | Onde | O que faz | Executor hoje |
| --- | --- | --- | --- |
| `SecureFileMove` | `organization/application` | mover com verificação; rollback | é a primitiva de todos |
| `EmptyDirectoryCleaner` | `organization/application` | apaga diretório que ficou vazio após organizar | Worker |
| `ExplorerRenameService` | `media/application/explorer` | renomeia pasta (`Files.move` direto) e arquivo (via move seguro) | **App** |
| `DefaultExplorerFileSystem` | `media/application/explorer` | apaga arquivo, com retry sobre atributo somente-leitura | **App** |
| `QuarantinePurgeService` | `quarantine/application` | apaga o arquivo em quarentena e a subpasta que esvaziou | Worker (purge) |
| `ConversionCommitService` | `conversion/application` | carimba no arquivo já colocado a data de modificação do original | Worker |
| `CatalogBackupService` | `backup/application` | grava o zip do backup num destino escolhido pelo usuário | **App** |

Os dois últimos **não estavam previstos em VIII.108** e são achados desta classificação.
`CatalogBackupService` já usa o move seguro exatamente porque o destino pode estar sob o watcher — o
próprio comentário no código diz isso.

### B — WORKSPACE: cache, temporário, artefato regenerável

`PhotoThumbnailService`, `VideoThumbnailService`, `PhotoPerceptualHashService`,
`ConversionFileNaming`, `LibraryCatalogCleanupService` (limpa o cache de thumbnails),
`BoundaryMetadataStore`, `BackupFolderResolver`, `WorkspaceBootstrapListener`,
`FirstAccessCredential`.

### C — INFRASTRUCTURE: banco, ferramentas, instalação, atualização, dataset

`PostgresProcessRunner`, `PostgresBuildSource`, `EmbeddedDatabaseInstaller`,
`ClusterPropertiesStore`, `PostgresDumpProcessRunner`, `CatalogBackupService` (empacotar/desempacotar
o zip — a parte que mexe no próprio arquivo temporário), `ExternalToolInstaller`, `FfmpegBuildSource`,
`GeoBoundariesSource`, `BoundaryDatasetManager`, `UpdateInstallService`, `UpdateInstallation`,
`HttpReleaseDownloader`, `UpdateInstallProcessRunner`.

### D — AMBÍGUO, resolvido pela leitura do caminho

| Caso | Parecia | É | Por quê |
| --- | --- | --- | --- |
| `LibraryCatalogCleanupService` | biblioteca (o nome diz "library") | **workspace** | o `Files.deleteIfExists` cai em `workspace/cache/thumbnails`; o que ele faz com a biblioteca é apagar **linhas de catálogo** |
| `CatalogBackupService` | infraestrutura | **os dois** | empacotar é infraestrutura; entregar o zip no destino do usuário é LIBRARY, e é por isso que já passa pelo move seguro |
| `ConversionFileNaming` | biblioteca (nomeia pelo nome final) | **workspace** | resolve dentro de `workspaceManager.temp()` |
| `ConversionCommitService` | metadado, não mutação | **LIBRARY** | `setLastModifiedTime` sobre um arquivo do usuário é uma escrita que o watcher observa |

**Consequência para o enforcement:** uma regra que olhasse só `Files.move`/`Files.delete` marcaria 29
classes e erraria nas quatro linhas acima — em ambas as direções.

## VIII.143 Os ports

### `LibraryFileMutations` — a capacidade de mudar um arquivo do usuário

Interface em `shared/application/library`, cinco operações: `move` (verificado), `rollback`,
`renameDirectory`, `deleteFile`, `deleteEmptyDirectory` e `carryModifiedTime`. Nenhuma genérica — não
existe `mutate(Path, Consumer)` nem `rawMove`, porque uma operação genérica seria `Files` com um
nome diferente e devolveria a decisão a quem chama.

**Onde cada peça mora, e por quê essa divisão e não outra.** A avaliação das três alternativas de
VIII.140:

| Alternativa | Veredito |
| --- | --- |
| A — `SecureFileMove` vira a implementação | recusada: obrigaria a renomear e a alargar uma classe cujo nome e testes descrevem *movimento verificado*, e o port precisa de operações que não são movimento |
| **B — o port encapsula `SecureFileMove`** | **escolhida** |
| C — outra decomposição | avaliada e descartada: ver o parágrafo do `shared` abaixo |

A implementação, `SecureLibraryFiles`, fica em `organization/application` — **não** em `shared`. A
tentativa de pôr a implementação em `shared` esbarra numa regra do próprio projeto: o movimento
verificado depende de `OrganizationMoveVerifier`, que depende de `FileHashService` (domínio
`metadata`), e `shared` não pode depender de domínio. Subir a cadeia inteira significaria mover o
hash para `shared` — onde ele não pertence, porque só dois domínios o usam.

**A interface, essa sim, mora em `shared`**, e é o que resolve o problema de verdade: ela nomeia
apenas `Path`, `IOException` e `FileTime`. O efeito prático é uma melhora concreta de direção — o
explorer, a quarentena, a conversão e o backup **deixaram de importar `organization.application`**
para mover arquivos, e passaram a depender da capacidade em vez do domínio que a implementa.

### `CatalogMutations` — a capacidade de mudar o catálogo da coleção

Três operações, porque três existem: `markMissing`, `purgeMissingBefore`, `forgetLibrary`. A fronteira
que o enunciado pediu para não borrar está desenhada assim: **passa pelo port o que decide, para um
conjunto de arquivos de uma vez, que eles sumiram, estão ausentes ou deixaram de pertencer a algo.**
Salvar um fingerprint, gravar progresso de execução ou escrever metadata de um arquivo continua sendo
persistência comum e não passa por lugar nenhum novo — são fatos sobre uma coisa examinada, não
decisões sobre a coleção.

`CollectionCatalogMutations` é deliberadamente fina: as instruções set-based continuam no repositório,
onde o SQL e as regras de cascade estão escritos e testados. O que o port acrescenta não é cálculo, é
**fronteira** — um nome injetável que uma regra de arquitetura enxerga, onde um método de repositório
é indistinguível dos outros que qualquer classe pode legitimamente chamar.

## VIII.144 As regras, e o que cada uma prova de verdade

Em `MutationBoundaryArchitectureTest`, com ArchUnit 1.4.1 em escopo de teste.

| R | O que checa | O que **não** prova |
| --- | --- | --- |
| **P1** | toda classe que chama um método mutador de `java.nio.file.Files` está declarada como mutador de biblioteca (2 classes) ou como escritor de workspace/infraestrutura (23 classes) | **não sabe para onde o `Path` aponta** — isso é valor de runtime. Uma classe listada como workspace que passe a receber um caminho da biblioteca não é pega por esta regra |
| **P2** | só os consumidores declarados injetam `LibraryFileMutations` ou `CatalogMutations` | não distingue *usar* de *ter*: uma classe autorizada pode chamar o port em qualquer método seu |
| **P3** | nenhuma tela **compõe**, direta ou transitivamente, uma classe que detenha um port — fora das exceções nomeadas | segue o grafo de **campos**, não o de chamadas; uma dependência guardada numa coleção genérica (existe uma: o `Map` de handlers do dispatcher) é invisível |
| **P4** | a lista de escritores fora dos ports é fechada: uma classe nova que escreva em disco reprova o build até ser classificada | não julga a classificação; só exige que alguém a faça |
| **P5** | nada fora de `..infrastructure..` depende de `org.postgresql` | não impede mover código de aplicação para `infrastructure` só para contornar — isso continua sendo julgamento humano, e está dito na mensagem da regra |

**A honestidade sobre P1 é o ponto.** Ela não é "só o port pode mutar a biblioteca" — isso é
indecidível estaticamente. É "toda escrita em disco pertence a uma de duas listas, e mudar isso é uma
decisão consciente". O que a torna útil não é o que ela prova, é o que ela **obriga a declarar**.

### O que P3 ensinou, e por que a primeira versão era inútil

A primeira implementação seguia o grafo completo de dependências do ArchUnit e acusou **sete
violações** — praticamente todo consumidor do port. Investigado: num Spring monolítico, o grafo de
tipos (parâmetros, retornos, exceções, enums) conecta qualquer controller a quase tudo. Formulada
assim, P3 é verdadeira para todo mundo e não separa nada.

A versão que ficou percorre **o grafo de composição — os tipos dos campos**. É o que representa "esta
classe usa aquela", e é também o caminho pelo qual a capacidade viaja, já que o port é concedido por
injeção. Registrado como limitação declarada, não como detalhe: uma dependência escondida numa
coleção genérica não é vista.

### O que P3 encontrou depois de corrigida — quatro pontes reais

Com o grafo certo, sobraram caminhos concretos de tela até a capacidade de mutar. Três foram
**quebrados**, um virou exceção:

| Caminho | O que a tela realmente queria | Resolução |
| --- | --- | --- |
| `ConversionWebController` → `ConversionCommitService` | saber se **existe** pasta de quarentena configurada | quebrado: a leitura é de configuração e foi para `QuarantineFolderPolicy.root()` |
| `DuplicatesWebController` → `DuplicateDeletionLauncherService` → `QuarantineIntakeService` | a mesma leitura, antes de enfileirar | quebrado, mesma forma |
| `QuarantineWebController` → `QuarantineLauncherService` → `QuarantineIntakeService` | a mesma leitura | quebrado, mesma forma |
| `ExecutionWebController` → `OrganizationService` → `OrganizationAsyncRunner` → `OrganizationExecutor` | o **preview**, que roda na App e compõe o executor | exceção nomeada, removida em **4.2.7** |

As três primeiras são o mesmo defeito com três aparências: **um serviço de mutação estava sendo
injetado por uma tela para responder uma pergunta de configuração**. A leitura da pasta de quarentena
morava dentro de `QuarantineIntakeService` — a classe que move arquivos para lá — e foi para a
política que já trata da pasta de quarentena e já lê as configurações. Isso é o que o `AGENTS.md`
chama de utilidade cross-feature escondida numa classe de feature, e a regra a encontrou sem que
ninguém suspeitasse dela. Fica registrado como **V24**.

## VIII.145 A lista de exceções: dívida com data

Dez entradas, cada uma com classe concreta, motivo e o slice que a remove. Sem curinga, sem package,
sem entrada sem prazo — e um teste (`everyTemporaryExceptionNamesAClassAndTheSliceThatRemovesIt`)
verifica exatamente isso, além de exigir que a classe ainda exista com aquele nome.

> **Corrigido em VIII.152.1 (4.2.4).** Esta seção dizia "nove" e a tabela abaixo listava nove:
> faltava `LibraryCatalogCleanupService`, que estava na lista do código desde o primeiro dia. O teste
> valida a **forma** de cada entrada, não a contagem, e foi por isso que a diferença passou. A tabela
> abaixo está completa.

| Classe | Por que ainda | Sai em |
| --- | --- | --- |
| `ExplorerRenameService` | renomear pela tela de Arquivos responde enquanto o usuário espera | 4.2.4 |
| `DefaultExplorerFileSystem` | apagar pela tela de Arquivos, idem | 4.2.4 |
| `ExplorerDeletionService` | quarentenar uma seleção pela tela de Arquivos | 4.2.4 |
| `FileExplorerReconcileService` | a tela marca como ausente o que não encontrou ao listar | 4.2.4 |
| `QuarantineService` | restaurar **um** arquivo é uma conversa sobre aquele arquivo | 4.2.5 |
| `QuarantinePurgeService` | limpar registros cujo arquivo já sumiu ainda responde da tela | 4.2.5 |
| `CatalogFileRetentionService` | o purge de retenção roda no timer da própria App | 4.2.5 |
| `CatalogBackupService` | o backup grava o zip onde o usuário escolheu, que pode estar sob o watcher | 4.2.5 |
| `OrganizationService` | o preview roda na App e compõe o executor | 4.2.7 |
| `LibraryCatalogCleanupService` | esquecer uma biblioteca é parte da troca, que ainda não foi decomposta | 4.2.8 |

**A tendência é encolher, e o teste garante que encolher seja a única direção barata:** acrescentar
uma entrada exige editar a lista, escrever o motivo e escolher um slice — e o slice escolhido tem de
existir no intervalo 4.2.4–4.2.8. No fechamento da 4.2.8 a lista deve estar vazia.

## VIII.146 App × Worker, e o combined

Os ports **não** criaram caminho paralelo. O estado final continua sendo App → intenção; Worker →
handler → port → disco. As nove exceções existem porque a convergência não chegou nessas
capabilities, e cada uma carrega a data em que deixa de existir.

O combined não recebeu tratamento nenhum: as regras olham responsabilidade lógica — quem compõe quem —
e não processo. Uma classe da App que injetasse o port reprovaria igual, rodando na mesma JVM que o
worker ou não.

## VIII.147 V24 — leitura de configuração morando dentro de um serviço de mutação

**O defeito.** `QuarantineIntakeService.root()` lia a configuração `TRASH_FOLDER` e a normalizava. Três
lugares que só precisavam dessa resposta — o controller de conversão, o launcher de duplicados e o
launcher de quarentena — injetavam, para obtê-la, a classe que **move arquivos para a quarentena**.

**Por que importa.** Não é acoplamento estético: era o caminho pelo qual uma tela alcançava a
capacidade de mutar, e nenhum dos três chamadores queria isso. Uma leitura de configuração dentro de
um serviço de escrita transforma quem pergunta em quem pode escrever.

**Correção.** `QuarantineFolderPolicy.root()` — a classe que já valida a pasta de quarentena e já lê
`AppSettingService`. `QuarantineIntakeService.root()` delega para ela, e os três chamadores passaram a
depender da política.

**Severidade: média.** Não produzia comportamento errado; produzia a permissão errada.

## VIII.148 Medição da 4.2.3

```text
Tests:       3049 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98,35% instrução, 92,32% branch, 97,75% linha, 98,83% método, 100,00% classe
SpotBugs:    -Pspotbugs verify verde, BugInstance size is 0, nenhuma exclusão nova
ArchUnit:    P1-P5 verdes, 9 exceções nomeadas e datadas
Sonar:       0 issues abertas
```

**A cobertura caiu, e a queda é real** — 0,05 / 0,04 / 0,10 / 0,03 contra a 4.2.2. Verificado classe
a classe no relatório do JaCoCo: **nenhum método novo ficou descoberto**, e o que sobra são linhas de
`catch` de I/O nas classes que a extração reorganizou — `EmptyDirectoryCleaner` (7 linhas),
`LibraryCatalogCleanupService` (4), `SecureLibraryFiles` (3), `QuarantinePurgeService` (2),
`ExplorerRenameService` (1).

A causa é a própria extração, e é honesto dizê-la: cada caminho de falha que antes existia numa
classe passou a existir em duas — quem chama o port e o port. O `catch (IOException)` do
`EmptyDirectoryCleaner` continua lá, e agora existe também o `catch` do
`SecureLibraryFiles.clearReadOnly`; nenhum dos dois é alcançável sem o sistema operacional recusar
algo. Um deles **foi** coberto porque tinha uma forma honesta de o ser
(`stopsClimbingWhenThePortRefusesToRemoveAFolder`, com o port recusando a remoção como um filesystem
recusa uma pasta que voltou a ter conteúdo). O resto é o resíduo que a política já nomeia.

**O piso não foi tocado**, e a pendência histórica de VII.8 continua separada. Doze issues do Sonar
surgiram durante o trabalho — imports do próprio pacote e lambdas que cabiam em referência de método,
todas em código de teste que os scripts de migração produziram — e foram eliminadas antes do
fechamento.

**V23 (o aviso de encerramento do fork) continua registrado e inalterado.** Nada nesta fatia mexeu no
ciclo de vida do listener, e o aviso segue com a mesma forma e o mesmo efeito nulo sobre o resultado.

## VIII.149 Proposta da Fase 4.2.4 — Explorer

Proposta, não decisão, conferida contra o código depois da 4.2.3.

### O que a Parte VIII atribui à 4.2.4

VIII.112: rename de arquivo, rename de pasta, quarentenar, apagar; V2, V3, V12; dependente de 4.2.2
(espera) e 4.2.3 (port). VIII.86 acrescenta que V2/V3/V12 dependem de *wake-up + bounded wait*.

### O que a 4.2.3 deixou pronto, e o que isso muda

**Cinco das nove exceções da lista são do Explorer** — `ExplorerRenameService`,
`DefaultExplorerFileSystem`, `ExplorerDeletionService`, `FileExplorerReconcileService` e, por
tabela, o alcance de `QuarantineIntakeService`. A 4.2.4 é o slice que apaga a maior parte da lista, e
o teste de exceções é o critério de pronto: **a fatia termina quando essas entradas somem**.

O port já existe e as quatro operações que o Explorer faz já passam por ele. A migração deixa de ser
"reescrever a mutação" e passa a ser "mover quem chama": um handler novo, um payload, e a decisão de
UX sobre esperar.

### O que ainda não existe e a 4.2.4 precisa

1. **A primitiva de espera limitada** (adiada conscientemente na 4.2.2, VIII.134). É aqui que ela
   ganha consumidor real e forma decidível por evidência: qual orçamento, e se prende ou não a thread
   de request. **Continua sendo a decisão mais aberta da fatia.**
2. **Os tipos de execução** para rename, delete e quarentena pelo Explorer, com dedup por caminho.
3. **O que a tela faz quando não há Worker vivo** — o fato existe desde a 4.2.2 (`GET /api/worker`),
   a decisão de UX não. É a primeira capability que precisa dela.

### A pergunta de produto que a fatia não pode evitar

Renomear e apagar pela tela de Arquivos **respondem hoje enquanto o usuário olha**. Migrar para o
Worker significa que a resposta passa a vir de outro processo. Três desenhos possíveis, e a escolha é
sua:

| Desenho | O que o usuário vê | Custo |
| --- | --- | --- |
| espera curta (bounded wait) | igual a hoje, quando o worker está livre | precisa da primitiva; sob worker ocupado, vira espera visível |
| resposta imediata + atualização | a linha some/renomeia "sozinha" um instante depois | muda a sensação de imediatismo do gerenciador de arquivos |
| híbrido: espera até *T*, depois solta | igual a hoje na maioria dos casos | dois caminhos de UX para manter |

**Recomendo o híbrido**, que é o que a 4.1C modelou e o que o `AGENTS.md` sugere ao exigir que uma
ação recusada ou parcial termine em diálogo com motivo — mas é decisão sua, e é o que eu perguntaria
antes de começar.

### Sequência sugerida

1. tipos de execução + payloads + handlers, com o Explorer ainda respondendo síncrono;
2. a primitiva de espera, com o rename como piloto;
3. virar rename, depois delete, depois quarentenar — um de cada vez, cada um apagando sua entrada da
   lista de exceções;
4. `FileExplorerReconcileService`: avaliar se marcar ausentes ao listar continua fazendo sentido na
   App (é leitura que escreve) ou se vira parte do reconcile já enfileirado;
5. fechar com a lista de exceções reduzida a quatro entradas (4.2.5, 4.2.7 e 4.2.8).

### O que decidir antes

| # | Pergunta |
| --- | --- |
| 1 | Qual dos três desenhos de UX para rename/delete? |
| 2 | O orçamento da espera, se for híbrido — 1 s? 3 s? |
| 3 | Sem Worker vivo, a tela de Arquivos recusa a ação com explicação, ou aceita e mostra "aguardando processamento"? |
| 4 | `FileExplorerReconcileService` — migra junto ou vira parte do reconcile? |


---

# Fase 4.2.4 — Convergência do Explorer

## VIII.150 Reconciliação de escopo (feita antes de qualquer alteração de código)

Pela regra criada em VIII.128, o slice começa confrontando o enunciado com o backlog vigente e com o
código real. **Desta vez há divergência material**, registrada em VIII.152 — o código não foi tocado
antes de reportá-la.

### 1. O que o documento atribui à 4.2.4

VIII.112: rename de arquivo, rename de pasta, quarentenar, apagar; V2, V3, V12; dependente de 4.2.2
(espera) e 4.2.3 (port). VIII.86 acrescenta que V2/V3/V12 dependem de *wake-up + bounded wait*.
VIII.145 dá o critério de pronto pela lista de exceções datadas, e VIII.149 propõe a sequência e
enumera o que ainda não existe.

### 2. O que o enunciado atribui à 4.2.4

UX híbrida com orçamento de 1 s; Worker indisponível que não recusa; convergência do
`FileExplorerReconcileService` para o RECONCILE já enfileirado; remoção das exceções do Explorer;
mutação apenas pelos ports; preservação funcional; readiness sem bloqueio global; tipos, payload e
dedup; rename de arquivo e de pasta; V2; V3; V12; quarentena pelo Explorer; e a primitiva de espera
limitada, com o rename como primeiro consumidor.

### 3. Item do documento ausente do enunciado

**Nenhum.** As quatro operações de VIII.112 e as três dívidas (V2, V3, V12) estão todas no enunciado.

### 4. Item do enunciado atribuído pelo documento a outro slice

**Nenhum.** O enunciado é explícito em não antecipar a 4.2.5 — restore unitário, limpeza de ausentes
e purga de catálogo continuam onde estavam.

### 5. Decisões posteriores que desatualizaram a decomposição

| Decisão posterior | O que desatualizou | Como fica |
| --- | --- | --- |
| **4.2.3** pôs as quatro operações do Explorer atrás de `LibraryFileMutations` | VIII.112 falava em migrar *a mutação* | a mutação física já está no lugar certo; o que migra é **quem chama**. O enunciado já usa essa formulação |
| **4.2.2** entregou wake-up e saúde durável e adiou a espera (VIII.134) | VIII.86 tratava o *bounded wait* como pré-requisito genérico | a primitiva nasce aqui, com consumidor real, que é o que faltava para decidi-la por evidência |
| **4.2.3 / VIII.145** datou as exceções | VIII.112 não previa critério de pronto por lista | o critério passa a ser a lista — com a contagem corrigida em VIII.152 |
| **4.2.1 / V11** pôs o reconcile reativo na fila | VIII.112 não dizia para onde vai o reconcile do Explorer | existe um motor único ao qual convergir. E existe um defeito nele, que a convergência atravessa: **V25** |

### Veredito

**Há divergência material — três**, em VIII.152. Nenhuma invalida o escopo: duas corrigem o critério
de pronto e uma acrescenta uma correção ao slice. O trabalho funcional é o que o enunciado descreve.

## VIII.151 Os call paths reais do Explorer, reconstruídos

Levantados no código atual, depois da 4.2.3 — não herdados da discovery da 4.1.

| Ação | Entrada | O que a App faz hoje | `Execution` | Catálogo | Anúncio ao watcher |
| --- | --- | --- | --- | --- | --- |
| **Rename de arquivo** | `POST /api/files/rename` | guarda → nome válido → alvo livre → lock `ORGANIZATION` sobre origem+destino, espera 20 s → `port.move` | **nenhuma** | `file_key`, `file_name`, `extension`, no mesmo `@Transactional` | sem `executionId` (**V3**) |
| **Rename de pasta** | o mesmo endpoint, decidido por `Files.isDirectory` | idem, mas `port.renameDirectory` | **nenhuma** | **nada** (**V12**) | sem `executionId` |
| **Quarentenar** | `POST /api/files/delete?mode=QUARANTINE` | guarda → raiz configurada → lock `DEDUP_DELETE` sobre alvo+raiz → lista catalogados → **cria `Execution` direto em `RUNNING`** → `intake` por arquivo → remove a pasta vazia → finaliza a linha | criada fora de `enqueue → PENDING → claim` | `movement` + repoint, pelo intake | com `executionId` no move; a remoção da pasta vazia, sem |
| **Apagar de vez** | `POST /api/files/delete?mode=PERMANENT` | guarda → lock `QUARANTINE_PURGE` sobre o alvo → `deleteRecursively` → marca `DELETED` | **nenhuma** (**V2**) | `lifecycle_status = DELETED` | sem `executionId` |
| **Listar pasta** | `GET /app/files` | ao montar a listagem, marca `MISSING` o que o disco não tem | nenhuma | `MISSING` | n/a |

### O que a varredura procurou, e o que achou

| Procurado | Achado |
| --- | --- |
| Segundo motor da mesma capability | **nenhum** — cada ação tem um caminho só |
| `Files.*` fora dos choke points | **nenhum**; a 4.2.3 já os fechou |
| `Execution` criada direto em `RUNNING` | **um**: `ExplorerDeletionService.startExecution`, catalogado em VIII.130 como ponto de entrada de capability a migrar |
| Mutação sem `Execution` | **duas**: rename (arquivo e pasta) e apagar de vez |
| Self-write sem `executionId` | **três**: rename de arquivo, rename de pasta, apagar — e a remoção da pasta esvaziada pela quarentena |
| `AtomicBoolean`/`synchronized` como exclusão cross-process | **nenhum** — o Explorer usa `OperationLockService`, que é advisory lock no PostgreSQL |
| Fallback local quando o Worker não responde | **nenhum**, e nada nesta fatia cria um |
| Bloqueio por inventário em curso | **nenhum**: o Explorer não consulta `InventoryRunningState` (quem consulta são telas de configuração). É exatamente o comportamento que a decisão 8 manda preservar |
| Reconcile paralelo | **um**: `FileExplorerReconcileService`, que é o alvo da decisão 4 |

### V26 — o tipo de lock emprestado

As três ações tomam o lock declarando o tipo de **outra** operação: rename declara `ORGANIZATION`,
quarentenar declara `DEDUP_DELETE`, apagar declara `QUARANTINE_PURGE`. O tipo é o que a mensagem de
recusa nomeia (é a razão registrada em `OrganizationReconcileService` para o RECONCILE ter deixado
de pedir como `ORGANIZATION`), então quem conflitar com uma ação do Explorer hoje é informado de uma
operação que não está acontecendo.

**Severidade baixa**, e ele **se fecha sozinho** nesta fatia: cada ação passa a ter tipo próprio, e o
lock passa a ser tomado pelo dispatcher com o tipo da linha. Registrado para que o fechamento possa
apontar a evidência, não para virar trabalho separado.

## VIII.152 As três divergências materiais

### 1. A lista de exceções tem dez entradas, não nove — e o alvo real é 10 → 6

VIII.145 abre com "Nove entradas" e sua tabela lista nove. O código tem **dez**:
`MutationBoundaryArchitectureTest.TEMPORARY_CONSUMERS` inclui `LibraryCatalogCleanupService`
("forgetting a library is part of the switch, which is not decomposed yet", 4.2.8), que não aparece
na tabela do documento. O teste `everyTemporaryExceptionNamesAClassAndTheSliceThatRemovesIt` valida
a forma de cada entrada, não a contagem — por isso a diferença passou.

O enunciado conta **cinco** entradas do Explorer, mas uma delas — "alcance de
`QuarantineIntakeService` pelo fluxo do Explorer" — **não é uma entrada da lista**:
`QuarantineIntakeService` está em `WORKER_CONSUMERS`, como consumidor legítimo alcançável a partir de
`DuplicateDeleteJobHandler`. O que existe é o alcance da tela **através de**
`ExplorerDeletionService`, que já é uma das quatro entradas.

| Entradas do Explorer na lista real | Sai em |
| --- | --- |
| `ExplorerRenameService` | 4.2.4 |
| `DefaultExplorerFileSystem` | 4.2.4 |
| `ExplorerDeletionService` | 4.2.4 |
| `FileExplorerReconcileService` | 4.2.4 |

Restam **seis**: `QuarantineService`, `QuarantinePurgeService`, `CatalogFileRetentionService` e
`CatalogBackupService` (4.2.5), `OrganizationService` (4.2.7) e `LibraryCatalogCleanupService`
(4.2.8).

**Isso não muda o trabalho** — as quatro entradas do Explorer saem de qualquer forma. Muda o número
que o fechamento tem de exibir, e um critério de pronto com o número errado é precisamente o que se
declara cumprido sem que ninguém perceba. **Critério corrigido: 10 → 6.** A tabela de VIII.145 é
completada no fechamento desta fatia.

### 2. V25 — um RECONCILE não recursivo marca o subtree inteiro como `MISSING`

`OrganizationReconcileService.scan` monta os dois lados da comparação com escopos diferentes:

- **disco** — `scanDisk` respeita `recursive`: com `false`, é `Files.list`, só os filhos diretos;
- **catálogo** — `readDatabasePaths` usa **sempre** `descendantPattern`, isto é, a pasta *e todo o
  ramo abaixo dela*, sem olhar para `recursive`.

Com `recursive = false`, toda linha de catálogo de subpasta é comparada contra um conjunto de disco
que nunca poderia contê-la, cai em `addMissingOnDisk`, e `ReconcileApplier.apply` a marca `MISSING`.

**É alcançável hoje, por configuração documentada.** `WATCH_RECURSIVE` é um `AppSetting` editável
(onboarding e Configurações, default `true`), e tanto o reconcile agendado quanto o reativo passam
esse valor para a linha. Com ele desligado, a primeira passagem marca como ausente todo arquivo em
subpasta da biblioteca — e a retenção de catálogo, mais tarde, apaga essas linhas de vez.

**Severidade alta**: destrói estado do catálogo (e, passada a janela de retenção, história) sem erro
e sem aviso, exatamente como a regra de migrations do `AGENTS.md` descreve o dano silencioso.

**Por que pertence a esta fatia.** A decisão 4 manda o Explorer convergir para o RECONCILE
enfileirado, e o que o Explorer tem a pedir é o reconcile de **uma pasta** — não recursivo por
natureza. Convergir para um motor que erra justamente no caso não recursivo transformaria "abrir uma
pasta" em "marcar o ramo inteiro como ausente". Não é ampliação de escopo: é a pré-condição do item
4 do enunciado.

**Correção proposta:** ler o catálogo no mesmo escopo em que o disco foi varrido — sem
`descendantPattern` quando `recursive` é falso. Prova por comportamento observável: linhas de uma
subpasta sobrevivem a um reconcile não recursivo da pasta acima, e continuam sendo marcadas quando
ele é recursivo.

### 3. A recusa "ocupado" deixa de existir — e isso é consequência das decisões 1 a 3

**Hoje:** as três ações esperam até 20 s pelo lock e, se não o obtêm, respondem
`backend.files.busy` — "recurso ocupado, tente de novo". É o que acontece quando um inventário
segura a árvore, que é o caso comum, já que o inventário cobre a biblioteca inteira.

**Depois:** a intenção é durável. O dispatcher que não consegue os locks devolve a linha à fila com
backoff e a retoma sozinho; a resposta, passado 1 s, diz "em processamento". A ação **acontece**, mais
tarde, em vez de não acontecer.

As decisões 1–3 autorizam isso explicitamente ("estourar 1 segundo NÃO cancela, NÃO falha, NÃO faz
requeue... significa apenas que a resposta deixa de esperar"). Fica registrado porque é mudança
observável de comportamento, e o enunciado (item 7) manda declará-las em vez de escolhê-las em
silêncio.

**O que não muda:** as recusas que continuam sendo decisão de política, respondidas na hora e com a
mesma mensagem de hoje — fora da biblioteca, caminho sumiu, não é arquivo físico, biblioteca não
configurada, quarentena não configurada, nome inválido, alvo já existe.

## VIII.153 As decisões desta mensagem, registradas antes da implementação

| # | Decisão | Consequência de desenho |
| --- | --- | --- |
| 1 | **UX híbrida**: enfileira duravelmente e espera por um orçamento curto; esperar é **só observar o estado durável** | nenhum `Future`, callback, mapa em memória ou caminho de conclusão App↔Worker. A espera lê a linha; a App não executa, não faz claim, não renova lease |
| 2 | **Orçamento de 1 s** | estourar não cancela, não falha, não faz requeue, não muda posse nem lease, não autoriza execução local — é orçamento da resposta |
| 3 | **Worker indisponível não recusa** | aceita e informa; a verdade vem de `worker_instance` via `WorkerAvailability`, nunca de `WorkerSupervisor`, handle de processo ou estado em memória |
| 4 | **Reconcile converge**, sem enfileirar por reflexo | um motor só; o pedido sai apenas quando a listagem de fato encontrou ausentes. Depende de **V25** corrigido |
| 5 | **Enforcement**: as quatro entradas do Explorer saem, sem exceção nova | alvo corrigido **10 → 6**; ArchUnit P1–P5 é a prova, junto da verificação dos call paths |
| 6 | **Ports**: toda mutação nova passa por `LibraryFileMutations`/`CatalogMutations` | nenhum `Files.*` novo em handler ou service; `SecureFileMove`, self-write e `executionId` preservados |
| 7 | **Escopo funcional preservado** | validações, mensagens, diálogos, conflitos e resultados iguais; as mudanças inevitáveis estão declaradas em VIII.152.3 |
| 8 | **Readiness sem bloqueio global** | nada de "há processamento rodando → bloquear"; a granularidade continua sendo o lock de caminho, e o Explorer segue sem consultar `InventoryRunningState` |
| 9 | **Três tipos de execução próprios** — rename, quarentena, delete | um `ExecutionType` por handler; payload versionado; os caminhos vão nas colunas, que é de onde o dispatcher tira os locks |
| 10 | **Dedup pela identidade real do comando** | rename: origem **e** destino; quarentenar e apagar: o caminho canônico. Tipos distintos nunca colapsam entre si, porque o índice é `(execution_type, dedup_key)` |
| 11 | **Rename de arquivo migra inteiro** | catálogo reescrito no commit da própria execução; nenhum caminho síncrono remanescente na App |
| 12 | **V12**: o repath do ramo pertence à Execution do rename de pasta | passa por `CatalogMutations` (é decisão em bloco sobre a coleção); sem RECONCILE depois, porque não sobra divergência para reconciliar |
| 13 | **V2**: apagar de vez ganha Execution e história | **não** ganha cancelabilidade nesta fatia: o ponto de não retorno é a primeira remoção, e cancelar no meio deixaria uma pasta pela metade. Decisão separada, declarada em aberto |
| 14 | **Quarentena pelo Explorer migra a intenção** | regras da quarentena preservadas; nada de 4.2.5 é antecipado |
| 15 | **V3**: todo anúncio ao watcher passa a carregar o `executionId` | inclui a remoção da pasta esvaziada, que hoje anuncia sem id |
| 16 | **Bounded wait**: primitiva observadora, com o rename como piloto | avaliação explícita de thread de request antes de escolher a forma |


## VIII.154 V25 fechado: o reconcile raso comparava universos diferentes

A varredura de disco respeitava `recursive`; a leitura do catálogo não. Com `recursive = false`,
`scanDisk` listava **um** nível e `readDatabasePaths` pedia a pasta **e todo o ramo abaixo dela** —
então toda linha de subpasta era comparada contra um conjunto que nunca poderia contê-la, caía em
`addMissingOnDisk` e era marcada `MISSING` por `ReconcileApplier`.

**Correção:** a leitura passou a ter os dois escopos. `findForShallowReconcile` casa por
`current_folder = :sourcePath` — o mesmo universo que `Files.list` enxerga —, e o caminho recursivo
continua com o `descendantPattern` de sempre. Casar por igualdade na pasta armazenada, em vez de por
prefixo do caminho, também deixa a consulta rasa fora do problema de `LIKE` com backslash que a regra
de persistência do `AGENTS.md` descreve.

**Prova (`ShallowReconcileScopeIntegrationTest`, Postgres real):** numa pasta com um arquivo presente,
um ausente e uma subpasta com outro ausente, a passagem rasa marca **só** o ausente do primeiro nível
e deixa o descendente `ACTIVE`; a recursiva marca o que sumiu no fundo da árvore, como sempre fez.
Contra Postgres, e não sobre mock, porque o que mudou foi **qual consulta** a passagem faz — um teste
que dublasse a resposta provaria apenas que o dublê foi devolvido.

**Alcance do defeito, para o registro:** `watch-recursive` é `AppSetting` editável (Onboarding e
Configurações, default `true`), e tanto o reconcile agendado quanto o reativo repassam esse valor. Com
ele desligado, a primeira passagem marcava como ausente todo arquivo em subpasta da biblioteca — e a
retenção de catálogo, mais tarde, apagava essas linhas de vez. Silencioso por construção: um reconcile
que marca coisas como ausentes é um reconcile fazendo o seu trabalho.

## VIII.155 O protocolo do Explorer: três comandos

| Tipo | `source_path` | `target_path` | `dedup_key` | Handler |
| --- | --- | --- | --- | --- |
| `EXPLORER_RENAME` | o item | o nome novo, já resolvido | caminho canônico + `>` + nome novo | `ExplorerRenameJobHandler` |
| `EXPLORER_QUARANTINE` | o item | a raiz da quarentena | caminho canônico | `ExplorerQuarantineJobHandler` |
| `EXPLORER_DELETE` | o item | — | caminho canônico | `ExplorerDeleteJobHandler` |

**Três tipos, não um tipo com uma ação dentro.** O dispatcher escolhe o handler por esse valor, a tela
de execuções nomeia por ele, e — o que decide — o índice de deduplicação é `(execution_type,
dedup_key)`: um tipo só faria "renomear" e "apagar" o mesmo caminho parecerem o mesmo pedido.

**Nenhum payload, e isso foi confrontado com o modelo real antes de decidir.** O enunciado previa
payloads; o inventário e o reconcile não têm nenhum, porque a pasta é a coluna. Aqui é o mesmo caso: a
intenção inteira é (tipo, origem, destino), e as duas colunas **precisam** existir de qualquer forma,
já que é delas que o dispatcher tira os locks. O nome novo é o *file name* do destino — carregá-lo
também num payload seria um segundo lugar para a mesma verdade, com uma versão de esquema para manter.
Nada aqui depende de sessão web, de objeto em memória, de callback ou de contexto de requisição: a
linha basta para outro processo executar o comando dias depois.

**As duas pontas vão nas colunas porque são as duas que precisam ser seguradas.** Na quarentena, a
árvore que se esvazia e a pasta que se enche; no rename, origem e destino, que é o que impede uma
organização de mover um arquivo para exatamente o nome que este rename está criando.

## VIII.156 A espera limitada, e por que ela é só uma observação

`ExecutionCompletionWait.awaitTerminal(id, budget)` lê a linha, dorme 50 ms, lê de novo, até um estado
terminal ou até o orçamento acabar. É tudo o que ela faz. Não faz claim, não renova lease, não possui
nada e não guarda estado entre chamadas — o que ela sabe da execução é o que a linha diz, que é o que
qualquer um leria.

**A forma alternativa foi descartada explicitamente:** um registro de *futures* completadas pelo
Worker poria metade do resultado de uma execução no heap de um processo e faria da fila uma dica. O
enunciado proíbe, e a proibição é a mesma coisa que a 4.1C decidiu ao recusar notificação
Worker → App.

**Estourar o orçamento não é um desfecho.** Nada é cancelado, nada falha, nada volta para a fila,
posse e lease ficam intactos e a execução segue exatamente como seguiria; o que muda é o que o método
devolve.

**A avaliação de thread, que o enunciado pediu para não presumir.** A App é Tomcat com thread por
requisição (não há `spring.threads.virtual` ligado), então esperar segura uma thread do pool por até
1 s. Com o teto de 200 threads do Tomcat e um produto local de um usuário, isso é uma fração
irrelevante — e a alternativa (`DeferredResult` com um agendador) não elimina a espera, apenas a move
para outro pool, ao custo de um caminho assíncrono a manter em três endpoints. **Bloquear a thread por
um orçamento de 1 s foi a escolha, e a razão está registrada aqui em vez de ficar implícita.** O que
seria inaceitável — e é o que a 4.1C alertava — é usar isso como padrão geral para esperas longas.

**Não é transacional, e não pode ser chamada de dentro de uma transação.** Cada olhada é uma leitura
curta própria, que é o que permite à seguinte enxergar o que outro processo comitou; dentro de uma
transação o Hibernate responderia do contexto de persistência que carregou primeiro, e a espera
consultaria um retrato até o orçamento acabar.

## VIII.157 V12 fechado, e o defeito irmão que ele revelou (V27)

**V12** era o rename de pasta que não reescrevia o catálogo. Agora reescreve, dentro da própria
execução: `CatalogMutations.repointFolder` — a quarta operação do port, acrescentada com a mesma régua
das outras três (decide, para um conjunto de arquivos de uma vez, que a coleção está em outro lugar).

A instrução casa o prefixo por `left(file_key, length(:oldPrefix))` em vez de `LIKE`, pelo motivo que
o `AGENTS.md` registra: caminho Windows é feito de backslash, que é o escape do `LIKE`, e nome de
arquivo é feito de `_` e `%`, que são os curingas dele. Comparar uma cabeça de tamanho fixo faz a
mesma pergunta sem nada disso. A versão da linha é incrementada à mão, porque uma instrução em bloco
passa por fora do *optimistic locking* — o mesmo motivo pelo qual `markMissingByIds` já fazia isso.

**Não há RECONCILE depois.** O catálogo fica consistente no commit da operação, então enfileirar uma
reconciliação seria pedir para conferir o que acabou de ser escrito. É exatamente o que o item 12 do
enunciado pedia para não fazer nas duas direções: nem deixar a janela aberta, nem duplicar o trabalho.

**V27 — o defeito irmão, encontrado ao migrar.** O rename de **arquivo** reescrevia
`catalog_file.file_key` e deixava `catalog_file_location.current_path` apontando para o nome antigo.
É precisamente o "caminho obsoleto" que a reconciliação tem uma rotina para reparar — e, até uma
passagem reparar, a tela de Arquivos (que lê a *placement*) mostrava o arquivo renomeado como ausente,
ao lado de uma cópia não registrada de si mesmo. Corrigido em `ExplorerRenamePersistence`, que move as
duas linhas na mesma transação. Severidade **baixa** (auto-reparável), registrado porque foi
descoberto aqui e fechado aqui.

**Prova:** `FolderRepointIntegrationTest`, contra Postgres, com caminhos Windows contendo `_` e `%`:
o filho direto e o descendente profundo mudam de `file_key`, `current_path` e `current_folder`; a
pasta vizinha cujo nome apenas *começa* igual (`album_2009` ao lado de `album_2008`) não é tocada; e
`original_path` continua onde estava, porque é história.

## VIII.158 V2 fechado: apagar de vez passa a ter história

O "apagar de vez" era o efeito mais irreversível do produto e o único que não deixava registro algum.
Agora é uma `Execution` como qualquer outra: tipo próprio, o caminho, quando, qual processo executou,
quantos arquivos foram e qual foi o desfecho — a diferença entre "minhas fotos sumiram" e uma resposta.

**Ter linha não o torna cancelável, e isso foi decidido em vez de acontecer.** O ponto de não retorno é
a **primeira remoção**: antes dela nada foi destruído, depois dela cada arquivo que saiu já saiu. Parar
no meio deixaria uma pasta pela metade, que é pior do que qualquer uma das duas pontas, e ganhar
cancelamento de brinde — porque agora existe uma linha contra a qual apertar um botão — seria mudar o
que o comando faz de carona na migração. A posse é confirmada uma última vez imediatamente antes da
fase destrutiva, que é o momento em que parar ainda não custa nada.

**Consequência declarada:** a tela de progresso oferece "Cancelar" para qualquer execução em
`RUNNING`, e para estas três o pedido não terá efeito. O botão só é alcançável navegando até a
execução enquanto ela roda — um comando do Explorer dura de milissegundos a segundos — mas a
inconsistência existe e fica nomeada aqui em vez de escondida. Torná-la coerente é decisão da fatia
que decidir sobre cancelamento, não desta.

## VIII.159 V3 fechado: todo anúncio carrega o `executionId`

Os três efeitos do Explorer anunciavam ao watcher sem id, caindo no teto fixo de cinco minutos.
Agora:

| Onde | Antes | Agora |
| --- | --- | --- |
| rename de arquivo | `move(..., null)` | `move(..., execution.getId())` |
| rename de pasta | `renameDirectory` sem id | `renameDirectory(..., executionId)` — o port ganhou o parâmetro |
| apagar (cada arquivo e cada pasta) | `deleteFile(path, null)` | o `executionId` desce por `ExplorerFileSystem` até o port |
| a pasta esvaziada pela quarentena | sem id | idem |

O rename de pasta é o caso que mais importava: **uma** chamada ao sistema operacional move tudo o que
está embaixo, e o watcher recebe uma notificação por arquivo. Sem id, passados cinco minutos, ele
voltaria a ler as próprias escritas do produto como alteração externa e dispararia reconciliação sobre
uma operação em curso.

## VIII.160 O reconcile do Explorer, convergido

`FileExplorerReconcileService` não existe mais. O que ele fazia — marcar `MISSING` o que a listagem
não achou no disco — era um segundo motor de reconciliação, com a sua própria ideia do que "ausente"
significa, rodando no processo que responde requisições.

No lugar dele, `ExplorerReconcileLauncher`: a listagem **relata** e o Worker **repara**, pelo motor
único. Raso, porque uma listagem é rasa — e é por isso que V25 tinha de ser fechado antes.

**Não é reflexo.** Nada é pedido quando nada estava ausente, o que impede uma requisição por
visualização de página; e, quando algo estava, a chave de deduplicação é a pasta, então o refresh
automático de 15 segundos encontra o trabalho já enfileirado em vez de enfileirar de novo.

**O que se perderia se a listagem simplesmente parasse de reparar:** navegar é a única coisa neste
produto que olha para fora da biblioteca monitorada — o inventário e a passagem agendada só veem a
pasta configurada. Por isso a resposta não foi apagar o comportamento, e sim mover quem o executa.

## VIII.161 Enforcement: 10 → 6, e o que a P3 aprendeu de novo

As quatro entradas do Explorer saíram da lista de exceções. `ExplorerRenameService` e
`DefaultExplorerFileSystem` passaram para `WORKER_CONSUMERS` — seguram o port porque um handler os
alcança —, `ExplorerDeletionService` deixou de segurar port nenhum (ele passa pelo sistema de arquivos
e pelo intake) e `FileExplorerReconcileService` deixou de existir.

**A regra ficou mais forte no caminho.** A P3 caminhava pelo grafo de composição olhando o **tipo
declarado** de cada campo — e parava numa interface. Um campo `ExplorerFileSystem` esconderia
`DefaultExplorerFileSystem`, que segura o port, de qualquer tela que o alcançasse. Agora a caminhada
segue da interface para quem a implementa, que é o que o contêiner injeta de fato. Nenhuma violação
nova apareceu com isso, o que é a resposta que se quer de um aperto de régua.

**Sem exceção nova.** As seis que restam são as de sempre, com as datas de sempre.

## VIII.162 Deduplicação e concorrência, caso a caso

A pergunta do item 10 não era "qual chave", e sim "o que acontece com cada par de pedidos". A matriz,
com o comportamento que o código produz:

| Cenário | O que acontece | Por quê |
| --- | --- | --- |
| Dois pedidos equivalentes sobre o mesmo arquivo | colapsam; o segundo recebe a execução que já espera, e a espera de 1 s observa **aquela** | o índice parcial recusa o `INSERT` e `enqueueOrExisting` responde com a que existe |
| Dois renames concorrentes do mesmo *source*, nomes diferentes | **não** colapsam: são dois comandos | a chave inclui o nome novo. O segundo a rodar acha a origem ausente e termina `REJECTED` dizendo isso |
| Delete concorrente do mesmo arquivo | colapsa | chave = caminho canônico |
| Quarentena concorrente do mesmo arquivo | colapsa | idem |
| Rename × delete do mesmo arquivo | **não** colapsam | o índice é `(execution_type, dedup_key)`, e são comandos diferentes. Quem roda depois encontra o arquivo ausente e é `REJECTED` |
| Quarentena × delete | idem | idem |
| Retry da mesma intenção | é o primeiro caso: responde a execução em curso | nada é reenfileirado |
| Reclaim após perda do Worker | `INTERRUPTED` + RECONCILE **da pasta** | nenhum dos três é `resumable`: metade já aconteceu, e recomeçar partiria de um mundo que a primeira tentativa mudou |
| Cancelamento | não honrado nos três (VIII.158) | decisão, não omissão |
| Estado terminal | `FINISHED`, `FINISHED_WITH_ERRORS`, `REJECTED`, `ERROR`, `INTERRUPTED` | a tela mostra o que a linha diz; só `FINISHED` é sucesso |

**Idempotência, dita com todas as letras:** nenhum dos três é idempotente, e é por isso que
`resumable()` continua `false`. O que os torna seguros não é poder repetir, é o reclaim reconhecer
que não pode.

**O reclaim precisou de um ajuste por causa disto.** Ele enfileirava um RECONCILE das colunas de
caminho da execução abandonada, e as colunas dos comandos do Explorer nomeiam **um arquivo** — que,
quando alguém reclama, costuma ser exatamente o que não está mais lá. Um reconcile pergunta sobre uma
pasta e recusa um caminho que não é diretório, então a busca agora sobe até a pasta que existe, que é
onde a divergência está de qualquer forma.

## VIII.163 Readiness e concorrência com inventário

**Nenhum bloqueio novo, e nenhum global.** O Explorer não consulta `InventoryRunningState` (quem
consulta são telas de configuração), e nada nesta fatia mudou isso. A exclusão continua sendo a cadeia
de locks de caminho: exclusiva no caminho pedido, compartilhada nos ancestrais.

O que essa granularidade implica, dito honestamente: um inventário da biblioteca inteira segura a
raiz em modo exclusivo, então um rename **dentro** dela espera o inventário terminar. Isso não é
regra nova — é a semântica de lock que já existia. O que mudou é o desfecho: antes a operação
esperava 20 s e **falhava** com "ocupado"; agora ela é devolvida à fila pelo dispatcher e **acontece**
quando o conflito cessa. Arquivos independentes seguem utilizáveis o tempo todo, que é o que a 4.1F
exige.

## VIII.164 Medição da 4.2.4

Build limpo, PostgreSQL real, um único Maven na máquina:

```text
Tests:       3076 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98,43% instrução, 92,43% branch, 97,89% linha, 98,87% método, 100,00% classe
SpotBugs:    -Pspotbugs verify verde, BugInstance size is 0, nenhuma exclusão nova
ArchUnit:    P1-P5 verdes, 6 exceções nomeadas e datadas
Sonar:       0 issues abertas (2 surgiram e foram eliminadas antes do fechamento — ver abaixo)
```

**As cinco métricas subiram em relação à 4.2.3** — +0,08 instrução, +0,11 branch, +0,14 linha, +0,04
método, classe igual em 100% —, o que é o contrário do que costuma acontecer numa fatia que
acrescenta código. A razão é que os caminhos novos são caminhos com comportamento observável: um
comando é enfileirado, uma linha recebe um desfecho, uma pasta é reapontada. O que sobrou descoberto
foi procurado no relatório por classe e fechado onde havia teste honesto a escrever:

| O que faltava | Como foi coberto |
| --- | --- |
| `ExecutionCompletionWait.pause` — a interrupção | interromper a thread antes de esperar: ela para e devolve a flag |
| `ExplorerDeletionGuard` — o caminho não-físico | um `.lnk` dentro da biblioteca, que a política já recusa |
| `ExplorerDeletionService` — o desfecho `SKIPPED` do intake | um arquivo que o intake não leva é contado como mantido, e a pasta não é reportada como esvaziada |
| `ExecutionProgressService.finishCommand` | os quatro contadores que ele grava, afirmados um a um |

**O piso do README continua onde estava** (98,49 / 92,43 / 98,02 / 98,92 / 100,00). Ele é a pendência
histórica de VII.8 — um piso registrado acima do que a suíte mede desde a migração dos writers —, e
esta fatia **encurtou a distância** em vez de a aumentar: o branch passou a **cumprir o piso
exatamente**, e o que separa os outros três caiu para 39 instruções, 19 linhas e 2 métodos. Nada aqui
recalcula piso: isso é o procedimento explícito de *Recalcular o piso*, e continua sendo decisão à
parte.

**O resíduo declarado desta fatia**, verificado linha a linha no relatório: `ExecutionReclaim.folderOf`
tem 2 instruções e 1 branch inalcançáveis — o `Optional.empty()` de quando **nenhum** ancestral de um
caminho existe, que exige um volume inteiro ter sumido —, e `ExplorerDeletionService.catalogedUnder`
mantém o ramo do separador final, alcançável só por uma raiz de unidade, que o guarda recusa antes.
Nenhum dos dois é dívida: são o tipo de caminho que a política nomeia como resíduo aceito.

## VIII.165 Reconciliação final da 4.2.4, item a item

Confrontada contra o enunciado e contra as decisões registradas em VIII.153. Nenhum item foi omitido;
o que não foi feito está escrito como **NÃO IMPLEMENTADO** com motivo e destino.

| Item | Planejado | Implementado | Evidência no código | Teste/prova | Pendência |
| --- | --- | --- | --- | --- | --- |
| Rename de arquivo | migrar inteiro para o Worker | sim | `ExplorerRenameJobHandler` → `ExplorerRenameService.execute` | `ExplorerRenameServiceTest.movesTheFileUnderItsExecutionAndRepointsTheCatalog` | — |
| Rename de pasta | migrar + catálogo consistente no commit | sim | `repointCatalog` → `CatalogMutations.repointFolder` | `renamesAFolderAndMovesTheCatalogueOfItsWholeSubtree` + `FolderRepointIntegrationTest` | — |
| Quarentena pelo Explorer | migrar a intenção | sim | `ExplorerQuarantineJobHandler` → `ExplorerDeletionService.quarantine` | 8 casos em `ExplorerDeletionServiceTest` | — |
| Apagar definitivo | migrar + ganhar história | sim | `ExplorerDeleteJobHandler` → `deletePermanently` | `deletesAFileFromDiskAndMarksTheCatalogEntry`, `deletesAFolderWithEverythingUnderIt` | — |
| **V2** | execução e histórico para o apagar | **fechado** | `EXPLORER_DELETE` + `finishCommand` com contadores | idem acima | cancelabilidade **não** concedida, por decisão (VIII.158) |
| **V3** | `executionId` em todo self-write | **fechado** | `renameDirectory(..., executionId)`, `deleteFile(..., executionId)`, `deleteEmptyTree(..., executionId)` | `verify(libraryFileMutations).renameDirectory(folder, renamed, EXECUTION_ID)`; `SecureLibraryFilesTest` | — |
| **V12** | rename de pasta reescreve o ramo | **fechado** | `CatalogMutations.repointFolder` + as duas instruções nativas | `FolderRepointIntegrationTest` (3 casos, Postgres real) | — |
| **V25** (novo) | reconcile raso simétrico | **fechado** | `findForShallowReconcile` + `readDatabasePaths(source, recursive, …)` | `ShallowReconcileScopeIntegrationTest` (2 casos, Postgres real) | — |
| **V26** (novo) | tipo de lock emprestado | **fechado pela migração** | cada comando declara o próprio `ExecutionType`; o lock é tomado pelo dispatcher | `ExplorerCommandLauncherTest.queues*` | — |
| **V27** (novo) | *placement* não acompanhava o rename de arquivo | **fechado** | `ExplorerRenamePersistence.rename` move as duas linhas | `pointsBothTheEntryAndItsPlacementAtTheNewName` | — |
| Tipos de `Execution` | três próprios | sim | `EXPLORER_RENAME`, `EXPLORER_QUARANTINE`, `EXPLORER_DELETE` | `ExplorerJobHandlersTest` (tipo + delegação + `resumable`) | — |
| Payloads | previstos pelo enunciado | **deliberadamente nenhum** | a intenção é (tipo, `source_path`, `target_path`) | `queuesARenameNamingBothEndsSoTheWorkerCanLockThem` | justificado em VIII.155 |
| Dedup | por identidade real do comando | sim | rename: canônico + `>` + nome; demais: canônico | `keepsTwoDifferentRenamesOfTheSameFileApart` | — |
| Retry / reclaim | comportamento definido | sim | `resumable() = false` → `INTERRUPTED` + RECONCILE da pasta | `ExecutionReclaimTest.queuesAReconcileOfTheFolderWhenWhatItWasTouchingWasAFile` | — |
| Idempotência | avaliada | sim: **nenhum dos três é idempotente** | `resumable()` default `false` | `ExplorerJobHandlersTest` | — |
| Bounded wait de 1 s | primitiva nova, observadora | sim | `ExecutionCompletionWait`; `RESPONSE_BUDGET` no launcher | `ExecutionCompletionWaitTest` (3 casos) | — |
| Comportamento após o timeout | não cancela, não falha, não requeue | sim | `stillGoing(queued)` devolve `pending` | `answersThatTheWorkIsStillComingWhenTheBudgetRunsOut` | — |
| Worker indisponível | aceita e informa | sim | `WorkerAvailability.current()` decide **a frase**, nunca o desfecho | `acceptsTheCommandEvenWithNoWorkerAliveToRunIt` | — |
| Ausência de fallback local | nenhum caminho executa na App | sim | não existe método no launcher que mute nada | o mesmo teste: o arquivo continua no disco | — |
| `FileExplorerReconcileService` | convergir | **removido** | substituído por `ExplorerReconcileLauncher` | `ExplorerReconcileLauncherTest`, `FileExplorerServiceTest` | — |
| RECONCILE | um motor só, sem pedido por reflexo | sim | pedido apenas quando a listagem achou ausentes | `browseShouldListCurrentFolderAndAskForAReconcileOfWhatIsMissingOnDisk` | — |
| `LibraryFileMutations` | toda mutação de arquivo passa por ele | sim | nenhum `Files.*` novo em serviço ou handler | P1 verde | — |
| `CatalogMutations` | mutação de coleção pelo port | sim | quarta operação, `repointFolder` | P2 verde + `FolderRepointIntegrationTest` | — |
| `SecureFileMove` | preservado | sim | o rename de arquivo continua indo por `move` verificado | `verify(libraryFileMutations).move(file, renamed, false, EXECUTION_ID)` | — |
| Self-write / `executionId` | preservado e completado | sim | ver V3 | idem | — |
| Readiness | sem bloqueio global | sim | nada novo consulta `InventoryRunningState` | VIII.163 | — |
| Exceções ArchUnit | quatro do Explorer saem | sim | `TEMPORARY_CONSUMERS` com 6 entradas | `everyTemporaryExceptionNamesAClassAndTheSliceThatRemovesIt` | — |
| Meta 9 → 4 | do enunciado | **retificada para 10 → 6** | a lista real tinha 10 | VIII.152.1 | documentação de VIII.145 corrigida |
| P1 | toda escrita em disco classificada | verde | `everyClassThatWritesToDiskIsClassified` | build | — |
| P2 | só consumidores declarados seguram port | verde | `onlyDeclaredConsumersHoldAMutationPort` | build | — |
| P3 | nenhuma tela alcança port | verde, **e mais forte** | agora atravessa interfaces até a implementação | `noScreenReachesAMutationPort…` | — |
| P4 | lista de escritores fechada | verde | mesma regra da P1 | build | — |
| P5 | só `infrastructure` nomeia o driver | verde | `onlyInfrastructureNamesTheDatabaseDriver` | build | — |
| Ausência de segundo motor | nenhum | sim | o reconcile do Explorer sumiu; as três ações têm um caminho só | VIII.160 | — |
| Preservação funcional | validações, mensagens, diálogos | sim, com uma mudança declarada | as recusas de política continuam imediatas e com as mesmas chaves | `ExplorerCommandLauncherTest` (6 recusas) | a recusa `busy` deixou de existir — VIII.152.3 |
| Testes | suíte completa | sim | — | ver VIII.164 | — |
| JaCoCo | não regredir | ver VIII.164 | — | — | — |
| SpotBugs / find-sec-bugs | verde | ver VIII.164 | — | — | — |
| Sonar | sem issue nova | ver VIII.164 | — | — | — |
| Documentação | esta parte VIII | sim | VIII.150–VIII.166 | — | — |
| README | estado atual | sim | seção de perfis reescrita + bloco de qualidade | — | — |
| Versão | política do `AGENTS.md` | ver VIII.164 | `pom.xml` | — | — |
| `git diff` / `status` | conferidos | sim | ver VIII.164 | — | nada commitado, como manda a regra |

## VIII.166 As seis exceções que restam, e por que cada uma ainda existe

| Classe | O que ela ainda faz na App | Por que ainda | Sai em |
| --- | --- | --- | --- |
| `QuarantineService` | restaurar **um** arquivo | é uma conversa sobre aquele arquivo: colisão de nome e pasta de origem sumida são perguntas para a pessoa, não desfechos a relatar | 4.2.5 |
| `QuarantinePurgeService` | limpar registros cujo arquivo já sumiu | responde da tela, e a 4E decidiu mantê-la síncrona até a decomposição | 4.2.5 |
| `CatalogFileRetentionService` | purga de retenção | roda no timer da própria App (é o V5) | 4.2.5 |
| `CatalogBackupService` | grava o zip onde o usuário escolheu | o destino pode estar sob o watcher, e o backup é operação global | 4.2.5 |
| `OrganizationService` | o preview | roda na App e compõe o executor para montar o plano | 4.2.7 |
| `LibraryCatalogCleanupService` | esquecer uma biblioteca | parte da troca de biblioteca, que ainda não foi decomposta | 4.2.8 |

## VIII.167 Proposta da 4.2.5 — Quarentena e catálogo

Proposta, não decisão, conferida contra o código depois da 4.2.4.

### O que a Parte VIII atribui à 4.2.5

VIII.112: restore unitário, cleanup de ausentes, purga de catálogo; **V5**. Mesma dependência da
4.2.4, menor risco, e é a fatia que "fecha os writers interativos".

### O que a 4.2.4 deixou pronto

**Quatro das seis exceções restantes são desta fatia** — `QuarantineService`,
`QuarantinePurgeService`, `CatalogFileRetentionService` e `CatalogBackupService` —, então o critério
de pronto é o mesmo de agora: a lista cai de **6 para 2**. E o que faltava para migrar uma capability
interativa deixou de faltar: existe tipo próprio por comando, existe a primitiva de espera com um
consumidor provado, existe a resposta "em processamento" e existe a leitura de disponibilidade do
Worker.

O restore unitário é o caso que a 4.1C usou para justificar **não** migrar: a colisão de nome é uma
pergunta. Vale reexaminar isso com o que existe hoje — a pergunta pode ser respondida **antes** de
enfileirar (a tela já escolhe o destino), e o que sobra depois é um movimento como qualquer outro.

### O que ainda não existe e a 4.2.5 precisa decidir

1. **O que fazer com a conversa do restore unitário**: perguntar antes e enfileirar a resposta, ou
   manter a conversa na App e migrar só o movimento. É a decisão de produto da fatia.
2. **A purga de retenção (V5)** roda num `@Scheduled` da App. Migrá-la é transformá-la num comando
   como o purge da quarentena já é — o que também a torna visível e cancelável, o que ela hoje não é.
3. **O backup** é o único dos quatro que não é "mais um comando": ele escreve num destino escolhido
   pelo usuário, é global e a 4.1 o classificou como E8. Pode ser que a resposta certa seja mantê-lo
   na App e **retirar a exceção por outro caminho** — fazendo o zip ir para o workspace e a entrega no
   destino final passar pelo port com execução própria. Merece ser decidido, não arrastado.

### Sequência sugerida

1. purga de retenção (V5) — a mais mecânica, e fecha uma dívida numerada;
2. cleanup de ausentes — já tem mensagem e forma de comando;
3. restore unitário — depois da decisão de UX do item 1 acima;
4. backup — por último, porque é o único que pode terminar em "não migra, muda de forma".

### O que decidir antes

| # | Pergunta |
| --- | --- |
| 1 | Restore unitário: pergunta antes e enfileira, ou conversa na App com o movimento no Worker? |
| 2 | A purga de retenção, virando comando, deve aparecer na tela de execuções toda vez, ou só quando tiver o que apagar (como o purge agendado da quarentena já faz)? |
| 3 | O backup migra ou muda de forma? |

**A 4.2.5 não foi iniciada.** Nada além desta proposta foi escrito sobre ela.

## VIII.168 As duas issues do Sonar, e o que elas apanharam

Surgiram nesta fatia e foram eliminadas antes do fechamento, como manda a régua:

| Regra | Onde | O que era |
| --- | --- | --- |
| `java:S6885` | `ExecutionCompletionWait` | `Math.min(..., Math.max(...))` onde `Math.clamp` diz a mesma coisa numa chamada |
| `java:S8491` | `CatalogFileRepository` | Javadoc pendurado: o método novo entrou **entre** o Javadoc de `deleteWithinLibrary` e o método que ele descrevia |

A segunda vale ser registrada por ser um erro de edição que já aconteceu antes neste projeto:
inserir um membro âncorado na assinatura do vizinho, em vez de no início do Javadoc dele, deixa a
documentação órfã — descrevendo um método que não está mais logo abaixo. O Sonar pega; a revisão
humana raramente pega.


---

# Fase 4.2.5 — Quarentena e catálogo

## VIII.169 Reconciliação de escopo (feita antes de qualquer alteração de código)

Pela regra de VIII.128. **Há divergência material — uma**, registrada em VIII.171: a premissa que
sustenta a exceção do backup é refutada pelo código, e ela decide qual das três fronteiras do
enunciado é a correta. O código não foi tocado antes de reportá-la.

### 1. O que o documento atribui à 4.2.5

VIII.112: restore unitário, cleanup de ausentes, purga de catálogo; **V5**. VIII.166/VIII.167
acrescentam as quatro exceções e a observação de que o backup "pode terminar em não migra, muda de
forma".

### 2. O que o enunciado atribui à 4.2.5

Restore unitário decomposto em conversa (App) + movimento (Worker); cleanup de ausentes convergido;
V5 fechado com precheck barato; backup **permanece na App** com a escrita final reenquadrada; as
quatro exceções removidas pelo mecanismo certo; P1–P5 verdes.

### 3. Item do documento ausente do enunciado

**Nenhum.**

### 4. Item do enunciado atribuído pelo documento a outro slice

**Nenhum.** O enunciado é explícito em não reabrir o restore global do catálogo (item 12) nem
iniciar a 4.2.6.

### 5. Decisões posteriores que desatualizaram a modelagem

| Decisão / código posterior | O que desatualizou | Como fica |
| --- | --- | --- |
| A exclusão de varredura passou a cobrir a pasta de backup (`isApplicationOwned`), e o watcher a aplica **antes** do registro de self-write | a justificativa da exceção do backup ("o zip vai onde o usuário escolheu, que pode estar sob o watcher") | **refutada**: o watcher nunca vê essa pasta. Ver VIII.171 |
| `CatalogBackupService` já constrói o artefato em *staging* no workspace e só move o arquivo pronto | o item 10 do enunciado descreve isso como alvo | **metade já existe**: o que falta é a fronteira da entrega, não a do artefato |
| 4.2.4 provou a espera limitada com consumidor real | VIII.167 tratava a espera como hipótese para o restore | o restore unitário passa a ser o segundo consumidor, sem inventar mecanismo |
| `ExecutionType.QUARANTINE_CLEANUP` **já existe** e já tem rótulo, mas **não tem handler** — a App executa e cria a linha direto em `RUNNING` | VIII.112 tratava o cleanup como "capability na App" | o tipo já é o certo; falta o handler. Nenhum tipo novo aqui |
| `QuarantineOperationLog.start*` cria `Execution` direto em `RUNNING` para restore unitário e cleanup | VIII.130 catalogou os dois como pontos de entrada legítimos "enquanto a capability não migra" | os dois métodos desaparecem com esta fatia |

### Veredito

**Divergência material: uma**, em VIII.171. As outras três exceções confirmam-se como descritas.

## VIII.170 As quatro exceções, confrontadas com o código real

| Classe | Por que ainda alcança o port | A responsabilidade vai ao Worker? | Sai por migração ou por decomposição? | Regra que não pode mudar em silêncio |
| --- | --- | --- | --- | --- |
| `QuarantineService` | `QuarantineWebController.restore` → `restore(movementId, options)` → `moveBack` → `LibraryFileMutations.move` | **o movimento, sim**; a conversa, não | **as duas**: migração do movimento **e** decomposição da conversa para fora da classe que segura o port — senão a tela continua alcançando o port pelo mesmo caminho | os cinco desfechos (`CONFLICT`, `ORIGIN_MISSING`, `MISSING_IN_QUARANTINE`, `LOCKED`, `SKIPPED`), o `RENAME` por `FileNames.nextAvailable`, a pasta alternativa, e nunca sobrescrever |
| `QuarantinePurgeService` | `QuarantineWebController.cleanupAbsent` → `cleanupAbsent()` → cria `Execution` e apaga registros; a classe segura o port por causa do **purge**, que já é do Worker | **sim**, o cleanup | **migração**: a classe já está em `WORKER_CONSUMERS`; o que sobra é a tela deixar de alcançá-la | `MAX_PER_RUN`, a rechecagem sob o lock, nenhum delete físico, e **não criar linha quando não há nada ausente** |
| `CatalogFileRetentionService` | `CatalogFilePurgeScheduler` (timer da App) → `purgeMissingOlderThan` → `CatalogMutations.purgeMissingBefore` | **sim** | **migração**, com precheck barato no agendador | só `MISSING` é purgado; `DELETED` é da quarentena; janela não positiva desliga a purga |
| `CatalogBackupService` | `SettingsBackupWebController` → `CatalogBackupAsyncRunner` → `create()` → `LibraryFileMutations.move(built, target, true, null)` | **não** — ver VIII.171 | **decomposição da escrita final** | o artefato ser validado antes de ser guardado, aparecer inteiro em um passo, e a tela receber o `BackupFile` pronto |

## VIII.171 A divergência material: a premissa do backup foi refutada pelo código

**O que está escrito na exceção:** "o backup grava o zip onde o usuário escolheu, que pode estar sob
o watcher".

**O que o código faz:** `SelfWriteAwareFileChangeSource.worthAnInventory` descarta toda mudança sob
uma pasta que a aplicação declara sua — `isApplicationOwned` = quarentena **ou pasta de backup** — e
faz isso **antes** de consultar o registro de self-write. O comentário no próprio filtro diz por quê:
a pasta de backup é deliberadamente posta num drive sincronizado, muitas vezes dentro da biblioteca
observada, e um backup escrito ali pareceria centenas de MB de arquivos novos chegando.

Ou seja: **o destino do backup nunca é observável pelo watcher**, por regra explícita e proposital. O
anúncio de self-write que o port faz nessa entrega é peso morto — a mudança é filtrada um passo
antes.

### O que o port de fato contribui hoje nessa linha

| Contribuição | Vale para o backup? |
| --- | --- |
| anúncio ao watcher | **não** — filtrado antes, por política |
| baseline SHA-256 + verificação byte a byte do arquivo movido | **sim, e é real**: quando o destino é outro disco, o "move" é uma cópia, e ninguém mais a verifica |
| `rollback` | não é usado aqui |
| criação do diretório pai | sim, mas trivial |

### As três fronteiras do enunciado, contra essa evidência

| Opção | Veredito |
| --- | --- |
| **A — entrega vira `Execution` própria** | recusada: o destino não é observado, o zip não é mídia do usuário, e o resultado seria um handler existindo só para satisfazer o ArchUnit — que o próprio enunciado (item 11) manda evitar. Além disso `create()` deixaria de poder devolver o `BackupFile` pronto, mudando a tela |
| **B — outro mecanismo equivalente já existente** | não existe: nada mais entrega artefato pronto a um destino escolhido pelo usuário |
| **C — decompor e provar que o destino não é E1** | **é o que a evidência sustenta**: pasta declarada da aplicação, excluída da varredura e do watcher, com artefato próprio do produto — a mesma categoria de `WORKSPACE_AND_INFRASTRUCTURE_WRITERS` em que `CatalogBackupService` **já está** por causa do `pack`/`unpack` |

### O que C obriga a resolver, e é a pergunta aberta

Sair do port **não pode custar a verificação da cópia**. Duas formas honestas:

| Forma | O que dá | O que custa |
| --- | --- | --- |
| **(i) ler o zip entregue de volta** e validar as duas entradas (`ZipFile` confere CRC-32 por entrada ao ler) | verifica o que de fato importa — "o arquivo que guardei pode ser lido como backup" — e é a mesma técnica que o código já usa um passo antes, em `catalogDump.readable(dump)` | é verificação **diferente** da atual (integridade do artefato, não igualdade de bytes com a origem). Mudança observável a declarar |
| (ii) comparar SHA-256 origem × destino dentro do domínio de backup | idêntico ao de hoje | traz `FileHashService` (domínio `metadata`) para o backup, acoplamento que a 4.2.3 tinha acabado de remover |

**Recomendo (i)**, e é decisão sua porque muda a natureza da garantia numa operação de recuperação de
desastre.

## VIII.172 O restore unitário, decomposto: a conversa fica onde há quem responda

A pergunta 1 de VIII.167 tinha duas respostas possíveis, e o código escolheu a terceira: **as duas**.
A conversa continua na App, e o movimento inteiro vai para o Worker — o que só funciona porque a
conversa acontece **antes** de qualquer coisa ser enfileirada.

| Classe | O que faz | O que **não** pode fazer |
| --- | --- | --- |
| `QuarantineRestorePlanner` | lê o movimento, a cópia em quarentena e o destino; devolve **ou** a pergunta **ou** o arquivo exato a criar | nada: não segura port, não escreve, não enfileira |
| `QuarantineRestoreLauncher` | enfileira a conclusão da conversa e espera 1 s por ela | mover arquivo |
| `QuarantineRestoreJobHandler` | recebe a intenção e chama o loop | decidir qualquer coisa |
| `QuarantineService` | o loop de movimento, sob a linha que o Worker reivindicou | perguntar |

O que isso resolve, e que era o argumento da 4.1C para não migrar: **o worker nunca precisa perguntar
nada**, porque a pergunta já foi feita. Uma colisão de nome e uma pasta de origem sumida terminam a
requisição com uma resposta, e a tela abre o diálogo de sempre. O nome novo do `RENAME` é escolhido
por `FileNames.nextAvailable` **no planner**, com a pessoa ainda ali — nunca pelo worker, que estaria
escolhendo por ela.

O que o worker ainda pode encontrar é o mundo ter mudado no meio: o destino ocupado por outra coisa,
a cópia purgada, o caminho travado. Isso não vira pergunta — vira desfecho da execução, e a tela
recomeça a mesma conversa. É a diferença entre uma decisão pendente e um fato novo.

**Um invariante novo, e ele é do payload:** um destino decidido pertence a **um** arquivo. O handler
recusa um payload que traga destino com mais de um item, porque aplicá-lo a uma seleção mandaria
todos para o mesmo caminho, cada um sobrescrevendo o anterior. O payload subiu para a versão 2 por
causa desse campo.

**O que a tela ganhou:** um quarto desfecho, `PENDING`. Passado o segundo de espera, a resposta é
"aceito, está sendo feito" e a tela passa a acompanhar a linha em vez da requisição — exatamente como
o Explorer já fazia desde a 4.2.4.

## VIII.173 O cleanup de ausentes, convergido — e as três respostas que ele passou a ter

`QuarantinePurgeService.cleanupAbsent()` fazia a varredura e a remoção na mesma chamada, dentro da
requisição. Agora são duas coisas em lugares diferentes:

- **`QuarantineAbsenceScan`** — uma leitura, e só. Diz quais registros não têm arquivo agora.
- **`QuarantineCleanupJobHandler`** → `cleanupAbsent(ids, execution, ownership)` — olha **cada um de
  novo, sob o lock**, imediatamente antes de apagar o registro.

A lista que viaja é uma sugestão, nunca um veredito: um drive momentaneamente indisponível faz todos
os itens parecerem ausentes ao mesmo tempo, e a segunda olhada é o que impede isso de virar uma
limpeza em massa de registros válidos.

**Sem chave de deduplicação, de propósito.** O que a lista nomeia é o que estava ausente quando ela
foi lida, e duas leituras a um minuto de distância são dois conjuntos diferentes. A fila teria de
comparar listas para distingui-los — que é o que a segunda olhada já faz por item, e melhor.

**Nada ausente não enfileira nada.** Uma execução que rodaria, limparia zero e deixaria uma linha
dizendo isso é justamente o tipo de história que faz uma tela de trabalho real ficar ilegível.

E aí aparece o que a régua de interface exige: se nada foi feito, **a pessoa tem que ser avisada com
o motivo**. `QuarantineCleanupResult` passou a carregar três estados distinguíveis — nada a limpar,
em processamento, e concluído com N removidos — cada um com **a frase já resolvida pelo back-end**. A
tela não monta sentença nenhuma: quando nada aconteceu, ela abre o modal com o texto que recebeu.
Antes disso, `{"removed": 0}` chegava à tela e virava "0 registros removidos" na linha de status, que
se lê como sucesso.

## VIII.174 V5 fechado: a purga de catálogo passa a ter história

`CatalogFilePurgeScheduler` apagava linhas de catálogo num timer da própria App, sem deixar nada em
tela nenhuma. São linhas que ninguém recupera — anos de metadado extraído, hashes perceptuais que
custaram horas, localizações resolvidas —, e a única evidência de que a purga rodou era uma linha de
log.

Agora o timer **pede**: `CatalogPurgeLauncherService.launch(dias)` enfileira um `CATALOG_PURGE`, e
`CatalogPurgeJobHandler` o executa. Três decisões vão junto:

| Decisão | Por quê |
| --- | --- |
| **Tipo próprio**, não `QUARANTINE_PURGE` | aquele apaga arquivos que uma pessoa mandou apagar; este esquece linhas sobre arquivos que já sumiram. Uma tela que os chamasse igual estaria descrevendo duas tardes muito diferentes |
| **A janela viaja, o corte não** | o que está vencido é decidido quando a purga roda, não quando ela é pedida — senão um pedido esperando atrás de uma conversão longa apagaria pelo relógio de ontem |
| **Precheck barato antes de enfileirar** | resposta à pergunta 2 de VIII.167: `existsByLifecycleStatusAndLifecycleChangedAtBefore` decide *se pergunta*, nunca *o que apagar*. Um dia parado não deixa linha na tela — é o que a purga agendada da quarentena já fazia |

O fail-safe do agendador não mudou: uma janela ilegível resolve para um valor não positivo e
**desliga** a purga, em vez de apagar com um palpite.

## VIII.175 O backup pela fronteira C: entrega verificada, sem port

Decidida a divergência de VIII.171 pela opção **C**, e sem pagar o preço que a opção (i) daquela
seção cobrava.

`BackupDelivery` é a nova classe, e ela faz três coisas: calcula o SHA-256 do artefato pronto, move
para o destino, e calcula de novo — se não bater, **apaga o que escreveu** e falha, em vez de deixar
um arquivo que só parece um resgate. Continua sendo verificação byte a byte contra a origem, que é a
garantia que existia antes; o que saiu foi o anúncio ao watcher, que era peso morto, e o `rollback`,
que ali nunca era usado.

**O digest é calculado dentro do domínio de backup, não pelo `FileHashService` do `metadata`.** É
literalmente um número sobre um arquivo, e atravessar domínio para buscá-lo recriaria o acoplamento
que a 4.2.3 tinha acabado de desfazer.

O que **não** mudou, porque é o que a decisão preserva: a criação segue na App, o artefato continua
sendo montado em *staging* no workspace e só o arquivo pronto vai ao destino, `create()` continua
devolvendo o `BackupFile` à tela, e nenhuma `Execution` artificial foi inventada para satisfazer o
ArchUnit — que é exatamente o que o item 11 do enunciado mandava evitar.

## VIII.176 Enforcement: 6 → 2

A meta da fatia, cumprida e conferida pelo próprio teste:

| Classe | Para onde foi |
| --- | --- |
| `QuarantineService` | `WORKER_CONSUMERS` — segura o port porque um handler o alcança |
| `CatalogFileRetentionService` | `WORKER_CONSUMERS` — idem, via `CatalogPurgeJobHandler` |
| `QuarantinePurgeService` | já estava em `WORKER_CONSUMERS`; o que saiu foi a tela alcançá-lo |
| `CatalogBackupService` | deixou de segurar port nenhum; continua em `WORKSPACE_AND_INFRASTRUCTURE_WRITERS`, agora acompanhado de `BackupDelivery` |

Restam **duas**, as de sempre, com as datas de sempre: `OrganizationService` (4.2.7) e
`LibraryCatalogCleanupService` (4.2.8).

**Nenhuma exceção nova, e nenhum handler criado só para calar a régua.** O backup é a prova disso: a
saída dele da lista não custou uma `Execution` que ninguém pediu.

## VIII.177 Medição da 4.2.5

Build limpo, PostgreSQL real, um único Maven na máquina:

```text
Tests:       3118 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98,39% instrução, 92,48% branch, 97,79% linha, 98,85% método, 100,00% classe
SpotBugs:    -Pspotbugs verify verde, BugInstance size is 0, nenhuma exclusão nova
Sonar:       0 issues abertas
ArchUnit:    P1-P5 verdes, 2 exceções nomeadas e datadas
Migrations:  nenhuma - `execution_type` é `VARCHAR(30)` sem CHECK nem enum nativo, e `CATALOG_PURGE`
             cabe nela
```

**O branch subiu e passou o piso** (92,43 → 92,48); instrução, linha e método cederam entre dois e
dez centésimos. A causa não é código novo sem teste — é o que a fatia *acrescentou ao denominador*:
uma conversa que virou classe, dois launchers, dois handlers e um verificador de entrega, todos com
seus caminhos felizes e infelizes cobertos, mas cada um trazendo junto as suas guardas de I/O.

**A colheita honesta veio antes de qualquer conta.** O que foi encontrado e fechado:

| O que faltava | Como foi coberto |
| --- | --- |
| `BackupDelivery` — o descarte de um backup que chegou corrompido | o digest passou a ser um colaborador (`BackupDigest`), o que permite às duas leituras discordarem; sem isso a garantia central da entrega não era demonstrável, porque um arquivo não se corrompe sob demanda entre duas leituras |
| `BackupDigest` — ler além do primeiro buffer | três megabytes com o último byte alterado: uma implementação que digerisse só o primeiro bloco passaria despercebida |
| Guardas de payload dos três handlers novos | versão ausente e lista ausente, que são o caminho negativo que a régua de testes exige e não estavam exercitados |
| `QuarantineListing` — nome terminado em ponto | um item catalogado como `OTHER` cujo nome acaba em `.`: sem extensão a oferecer, nenhum visualizador é proposto |
| `QuarantineProgressService` — o cleanup | a limpeza virou pedido como os outros, então a tela tem de conseguir segui-la |

**E uma linha morta foi apagada em vez de coberta.** O `case SKIPPED` do laço de restauração não era
alcançável: manter um arquivo na quarentena é uma resposta que alguém dá, e ela passou a ser dada
antes de qualquer coisa ser enfileirada — o planner encerra o pedido com ela e nada chega ao laço. O
contador e o campo do resultado saíram junto.

**O piso do README continua onde estava** (98,49 / 92,43 / 98,02 / 98,92 / 100,00). O que separa a
medição do piso são **70 instruções, 33 linhas e 3 métodos**; o branch cumpre o piso e **não** o
elevou junto, porque cinco centésimos estão dentro da oscilação de até 0,16 que esta suíte tem entre
execuções — subir a régua para dentro da faixa de ruído transformaria variação normal em build
vermelho na próxima tarefa. Recalcular piso continua sendo decisão à parte (VII.8).

Duas execuções desta mesma árvore, sem uma linha alterada entre elas, mediram 98,38/92,46/97,78 e
98,39/92,48/97,79 — a oscilação documentada, observada de novo aqui. Os números acima são os da mais
recente, que é a do relatório que ficou no disco.

**O resíduo declarado desta fatia**, conferido linha a linha no relatório:

| Onde | Quanto | Por que nenhum teste honesto alcança |
| --- | --- | --- |
| `BackupDigest` | 7 instruções | o `catch` de `NoSuchAlgorithmException` para SHA-256, que a plataforma garante existir |
| `BackupDelivery` | 1 branch | destino sem pasta pai, que só acontece na raiz de uma unidade |
| `QuarantineListing` | 9 instruções, 4 branches | pasta de origem nula (um caminho que a montagem do item já não suportaria) e o `catch` de I/O ao medir a cópia |
| `QuarantineService` | 4 branches | as combinações intermediárias da guarda de arquivo órfão, que exigem o move falhar **e** o rollback ter sucesso em ordens específicas |

## VIII.178 Reconciliação final da 4.2.5, item a item

Confrontada contra o enunciado e contra as decisões de VIII.169–VIII.171. O que não foi feito está
escrito como tal, com motivo.

| Item | Planejado | Implementado | Evidência no código | Teste/prova | Pendência |
| --- | --- | --- | --- | --- | --- |
| Restore unitário — conversa | ficar onde há quem responda | sim | `QuarantineRestorePlanner`, que só lê | `QuarantineRestorePlannerTest` (13 casos) | — |
| Restore unitário — movimento | ir para o Worker | sim | `QuarantineRestoreLauncher` → `QUARANTINE_RESTORE` → `QuarantineService.restoreMany` | `QuarantineRestoreLauncherTest`, `QuarantineServiceTest` | — |
| Os cinco desfechos | preservados | sim | `CONFLICT`/`ORIGIN_MISSING`/`SKIPPED` no planner; `MISSING_IN_QUARANTINE`/`LOCKED` nos dois lados | os dois testes acima | — |
| `RENAME` por `FileNames.nextAvailable` | escolhido com a pessoa presente | sim | `QuarantineRestorePlanner.planDestination` | `picksTheNewNameWhenTheAnswerIsToRename` | — |
| Pasta alternativa | preservada | sim | `options.destinationFolder()` no planner | `plansIntoTheChosenAlternateFolder` | — |
| Nunca sobrescrever | preservado nas duas pontas | sim | planner recusa destino ocupado; o worker olha de novo | `keepsTheFileWhenTheDecidedDestinationWasTakenInTheMeantime` | — |
| Payload do restore | destino decidido viaja | sim, **schema 2** | `QuarantineRestorePayload.destination` | `queuesTheDecidedMoveNamingBothEnds` | — |
| Invariante novo | destino decidido é de um arquivo só | sim | guarda no `QuarantineRestoreJobHandler` | `refusesADecidedDestinationForMoreThanOneItem` | — |
| Cleanup de ausentes | convergido | sim | `QuarantineAbsenceScan` + `QuarantineCleanupLauncher` + `QuarantineCleanupJobHandler` | 4 + 6 casos | — |
| `MAX_PER_RUN` | preservado, e agora compartilhado | sim | `QuarantineConstants.MAX_PER_RUN`, lido pela varredura e pela passagem | `readsAtMostOneRunWorthOfRecords` | — |
| Rechecagem sob o lock | preservada | sim | `cleanupEach` olha `Files.exists` dentro do lock | `cleanupAbsentRemovesGoneRecordsAndKeepsPresentOnes` | — |
| Nenhum delete físico no cleanup | preservado | sim | só `deleteMovement` + catálogo órfão | idem | — |
| Nada ausente não cria linha | preservado | sim | `QuarantineCleanupLauncher.clearAbsent` | `queuesNothingWhenNoRecordIsAbsent` | — |
| **V5** — purga de retenção | virar comando | **fechado** | `CATALOG_PURGE` + launcher + handler | `CatalogPurgeLauncherServiceTest`, `CatalogPurgeJobHandlerTest` | — |
| Precheck barato | resposta à pergunta 2 de VIII.167 | sim | `existsByLifecycleStatusAndLifecycleChangedAtBefore` | `queuesNothingWhenNothingIsPastTheWindow` | — |
| Só `MISSING` é purgado | preservado | sim | o precheck e a purga nomeiam `LifecycleStatus.MISSING` | `asksOnlyAboutRecordsMissingForLongerThanTheWindow` | — |
| Janela não positiva desliga | preservado | sim | agendador e launcher recusam `days <= 0` | `CatalogFilePurgeSchedulerTest` (2 casos) | — |
| Backup | fronteira **C** | sim | `BackupDelivery` + `BackupDigest`; sem port, sem `Execution` | `BackupDeliveryTest` (5), `BackupDigestTest` (3) | — |
| Verificação da cópia | preservada como está | sim, **SHA-256 origem × destino** | digest antes e depois, e o arquivo é apagado se discordarem | `discardsTheBackupWhenWhatArrivedIsNotWhatLeft` | a opção (i) de VIII.171 **não** foi usada: a garantia não mudou de natureza |
| Sem acoplar ao `FileHashService` | exigido | sim | `BackupDigest` vive no domínio de backup | `BackupDigestTest` | — |
| *Staging* no workspace | preservado | sim | `CatalogBackupService` monta em staging e entrega o arquivo pronto | `CatalogBackupServiceTest` | — |
| `create()` devolve o `BackupFile` | preservado | sim | assinatura intocada | idem | — |
| Meta 6 → 2 | do enunciado | **cumprida** | `TEMPORARY_CONSUMERS` com 2 entradas | `everyTemporaryExceptionNamesAClassAndTheSliceThatRemovesIt` | — |
| P1 | toda escrita classificada | verde | `everyClassThatWritesToDiskIsClassified` | build | — |
| P2 | só consumidores declarados seguram port | verde | `onlyDeclaredConsumersHoldAMutationPort` | build | — |
| P3 | nenhuma tela alcança port | verde | `noScreenReachesAMutationPortExceptThroughTheCapabilitiesStillBeingMigrated` | build | — |
| P4 | exceções nomeadas e datadas | verde | `everyTemporaryExceptionNamesAClassAndTheSliceThatRemovesIt` | build | — |
| P5 | só infra nomeia o driver | verde | `onlyInfrastructureNamesTheDatabaseDriver` | build | — |
| Migration | avaliada | **nenhuma necessária** | `execution_type VARCHAR(30)`, sem CHECK | V1 do schema | — |
| i18n | chave nova em todos os idiomas | sim | `cleanupNothingAbsent`, `cleanupProcessing`, `restoreProcessing`, `catalog.purge*` | `BackendI18nTest`, `JavaScriptI18nTest` | — |
| Tela | avisar quando nada foi feito | sim | modal com a frase pronta do back-end | `theQuarantineReasonSurvivesUntilItIsDismissed` | — |
| Restore global do catálogo (item 12) | **não reabrir** | não tocado | — | — | segue como estava, por decisão do enunciado |
| 4.2.6 | não iniciar | não iniciada | — | — | — |

**Visibilidade, aproveitando o que a fatia liberou.** `QuarantineService` e `QuarantinePurgeService`
eram `public` porque a tela as alcançava; a tela deixou de alcançá-las, e as duas passaram a
package-private junto com os métodos que só os handlers do próprio pacote chamam. É a direção que a
regra manda seguir quando o uso legítimo muda — restringir, nunca ampliar.

## VIII.179 Complemento da auditoria: o artefato do backup passou a ser lido de volta

Auditoria pedida depois do fechamento da 4.2.5, antes da 4.2.6. Ela **não** encontrou duplicação
entre `BackupDelivery`/`BackupDigest` e o `SecureFileMove` que valesse extração — as ~15 linhas de
laço de digest são a única coisa que se repete, e o que decide comportamento diverge nos três pontos
que importam: o que se verifica (SHA + tamanho + origem sumiu × só SHA), o que se faz quando diverge
(preservar o destino para o chamador decidir × apagá-lo) e se sobrescreve. Encontrou outra coisa.

### O que estava aberto

Uma assimetria: o produto gastava **duas leituras completas** provando a fidelidade da entrega e
**nenhuma** provando a integridade do que produzia.

| Antes | Depois |
| --- | --- |
| dump validado (`pg_restore --list`) | dump validado (inalterado) |
| **pack sem verificação alguma** | **archive lido de volta: estrutura, entries e CRC-32** |
| entrega validada (SHA-256 origem × destino) | entrega validada (inalterada) |

A primeira leitura do ZIP acontecia no `restore()` — no dia do resgate, que é exatamente quando já
não há alternativa. O que fechou é a janela `dump válido → pack → ZIP possivelmente defeituoso`.

### As três garantias, que não se substituem

| | Pergunta | Mecanismo |
| --- | --- | --- |
| **A** | o dump é utilizável? | `catalogDump.readable` → `pg_restore --list`, que percorre o arquivo inteiro por ser comprimido |
| **B** | o contêiner que produzi é íntegro? | `BackupArchive.verify` — `ZipFile` (diretório central) + `ZipInputStream` (leitura integral, CRC-32) |
| **C** | o que entreguei é o que validei? | `BackupDelivery` — SHA-256 do staging × do destino |

SHA igual não prova que o ZIP era íntegro; CRC válido não prova que o destino é igual ao staging;
contêiner íntegro não substitui a validação do dump. As três respondem perguntas diferentes.

### Por que duas passagens, e não uma

Medido, não presumido — um probe rodou as duas APIs do JDK 25 contra os mesmos arquivos:

| Defeito | `ZipFile`, lendo tudo | `ZipInputStream`, lendo tudo |
| --- | --- | --- |
| DEFLATED com byte alterado | detecta (`EOFException` do ZLIB) | detecta (`ZipException`) |
| **STORED com byte alterado** | **aceita — 12822 bytes sem erro** | detecta: `invalid entry CRC (expected 0x… but got 0x…)` |
| **cauda removida (sem END header)** | detecta: `zip END header not found` | **aceita sem erro** |
| íntegro | aceita | aceita |

`ZipFile` lê o diretório central e **não** confronta o CRC armazenado; `ZipInputStream` confronta o
CRC ao chegar ao fim de cada entry e **nunca** lê o diretório central. Como o `restore()` abre com
`ZipFile`, um artefato sem END header é irrecuperável ainda que todos os dados estejam lá — e é
justamente o formato que um arquivo interrompido assume. Cada passagem pega o que a outra deixa
passar; não é I/O duplicado, são invariantes distintas.

Os quatro casos do probe viraram **testes permanentes** em `BackupArchiveTest`, e é essa a função
deles: quem tentar simplificar para uma passagem só quebra o teste do STORED (se ficar com
`ZipFile`) ou o da cauda (se ficar com `ZipInputStream`).

### O formato, fechado num lugar só

`BackupEntries` passa a nomear as duas entries que um backup deste produto tem — e as únicas que
pode ter. Três consumidores concordam por construção: o `pack`, o `verify` e o `restore`. A
verificação é de **conjunto exato**, com duplicata recusada por ocorrência (não por conjunto: um
arquivo com `manifest.json` duas vezes é ambíguo para qualquer leitor, e ambiguidade num backup é
defeito).

**Compatibilidade intocada, por construção:** `verify` é chamado num único ponto — sobre o arquivo
em *staging*, dentro do `create()`. Nenhum caminho de leitura (`restore`, `list`, `delete`) passa por
ele, então endurecer o formato não alcança nenhum backup já gravado.

**Zip Slip não tem caminho real aqui:** o `unpack` do restore copia para um caminho que a aplicação
constrói (`…/nimbus-catalog-restoring.dump`), nunca derivado do nome da entry. Nenhuma entry é
materializada por nome, então a whitelist é a invariante forte e não há mitigação de traversal a
inventar.

### Falha

Falhar na validação lança `IllegalStateException` — deliberadamente **não** `IOException`, que o
`create()` traduz numa mensagem sobre o destino. Nada foi escrito no destino nesse ponto, e nada
deve ser: a entrega não acontece, o backup anterior continua onde está, o `finally` limpa o staging
pela política que já existia, e o erro chega à tela pelo mesmo mecanismo de sempre
(`CatalogBackupAsyncRunner.lastError`) com a razão específica. A falha de SHA pós-entrega continua
apagando o destino inconsistente, como já fazia.

### O que não foi feito, e por quê

Nenhuma releitura do ZIP no destino. Com o staging validado por A e B e `SHA-256(destino) ==
SHA-256(staging)`, o destino **é** aquele artefato bit a bit — reler suas entries seria uma terceira
leitura completa sem informação nova. E nenhuma segunda validação semântica do dump extraído do ZIP:
o dump foi validado na origem, e o CRC prova que os bytes empacotados são os mesmos.
## VIII.180 Slice 4.2.6 — a similaridade deixa de ser um cálculo e passa a ser um resultado

A pergunta que a 4.2.6 responde não é "quem executa a similaridade", é **o que a tela lê**. Antes,
ler era executar: a tela pedia `groups(threshold, pageable)`, o serviço agrupava tudo em memória e um
cache por processo guardava a resposta até o próximo restart. O resultado não existia fora do heap de
quem o calculou.

Depois, o Worker é o **único** motor, e o que a tela lê é uma linha de banco. A separação é literal:

| Papel | Classe | O que faz |
| --- | --- | --- |
| Motor | `PhotoSimilarityService` · `VideoSimilarityService` | `analyze(threshold, progress)` — agrupa e devolve; **nunca** decide quando rodar |
| Fila | `SimilarityLauncher` | escreve a `Execution` com `dedup_key` e payload |
| Execução | `SimilarityJob` + `PhotoSimilarityJobHandler` / `VideoSimilarityJobHandler` | valida, roda o motor, publica, encerra |
| Escrita | `SimilarityPublisher` | `build()` longa (BUILDING) + `publish()` curta (`REQUIRES_NEW`) |
| Leitura | `SimilarityResultReader` → `SimilarityViewService` | o que está ACTIVE, e o estado da tela |

Nenhuma classe acumula dois papéis, e as duas de motor voltaram a ser package-private: fora do
pacote, ninguém tem como chamá-las.

## VIII.181 O que foi removido, no mesmo passo

A regra de dívida técnica manda o antigo sair junto com o novo, e saiu:

`PhotoSimilarityAsyncRunner`, `VideoSimilarityAsyncRunner`, `SimilarityGroupingRunner`,
`SimilarityGroupCache`, `CachedGroups`, `SimilarityCaches`, a interface `SimilarityGrouping` (de
`application`, homônima da entidade nova), e os métodos `groups()`, `isCached()`, `cachedPage()`,
`computeAndCache()`, `evictFromCache()` e `invalidateCache()`. Com eles saiu a query
`MediaFingerprintRepository.fingerprintSignature` — a heurística de validade do cache (contagem +
maior id + maior `computedAt`), que não tem sucessor: quem responde "isto ainda vale?" agora é a
comparação de digests.

**V8 e V9 fecham por desaparecimento**, não por correção: não há mais `AtomicBoolean` nem
`synchronized` de bean guardando exclusão, porque não há mais runner. A exclusão passou a ser o
índice único parcial de `execution (execution_type, dedup_key)` para PENDING/RUNNING, que já existia
e vale entre processos — que é o que um `AtomicBoolean` nunca valeu.

## VIII.182 A chave de validade, refeita: família, composição, e por que são duas

V15 e V16 são o mesmo defeito visto de dois ângulos: a chave de validade não cobria nem as exclusões
nem os parâmetros. A resposta separa **identidade** de **conteúdo**, porque as duas mudam por razões
diferentes e produzem consequências diferentes:

| | `SimilarityFamily` | `SimilarityComposition` |
| --- | --- | --- |
| Responde | *que análise é esta* | *sobre quais arquivos ela foi feita* |
| Campos | `mediaType`, `algorithmId`, `groupingVersion`, `parametersDigest` | digest, elegíveis, analisados, teto, política |
| Muda quando | o algoritmo, a versão ou **qualquer parâmetro efetivo** muda | um arquivo entra, sai, é movido ou é excluído |
| Efeito | é **outra família** — o resultado antigo continua ACTIVE na sua própria | o resultado continua válido, e se declara **desatualizado** |

`parametersDigest` cobre `minSimilarity`, o raio de candidatos do pHash, o teto, a política de
seleção **e a assinatura das exclusões** — V15 e V16 no mesmo campo, porque uma exclusão é um
parâmetro do que a análise pode ver. O digest é SHA-256 sobre uma serialização **prefixada por
comprimento** (`nome:tamanho:valor`), que torna a codificação injetiva: nenhum par de entradas
distintas produz a mesma cadeia, que é o defeito clássico de concatenar com separador.

## VIII.183 O digest de composição, e por que App e Worker não podem divergir

A App decide **o que pedir**; o Worker registra **o que analisou**. Os dois escrevem um digest, e a
tela compara os dois para dizer "desatualizado". Se calculassem diferente, ou tudo pareceria velho
para sempre, ou nada pareceria.

A garantia **não** é um contrato documentado entre Java e SQL — é não haver dois cálculos.
`SimilarityGroupSupport.canonicalComposition` é a única seleção que existe, e roda dos dois lados
sobre linhas que as duas queries ordenam igual. A App a roda sobre a projeção leve; o Worker, sobre
as linhas pesadas que ele já ia carregar — e então filtra as pesadas *pelo que a seleção escolheu*,
em vez de aplicar um filtro equivalente pela segunda vez.

O caso que exigiu isso é o do vídeo: a query pesada devolve **uma linha por frame**, e o teto pode
cortar um vídeo no meio dos seus frames. A de-duplicação **por consecutivos** (e não por conjunto)
reproduz esse corte exatamente, então o vídeo lido pela metade aparece dos dois lados ou de nenhum.
`SimilarityCompositionAgreementTest` prende os três casos: foto, vídeo com múltiplos frames, e vídeo
cortado pelo teto.

## VIII.184 Publicar sem nunca mostrar meia resposta

Três estados e um índice, e o resto é consequência:

```
BUILDING --publish()--> ACTIVE --supersedeActive()--> SUPERSEDED
```

- **Ninguém lê BUILDING.** `findActive` filtra por status ACTIVE; um agrupamento em construção é
  invisível para toda a aplicação, inclusive para a própria tela que o pediu.
- **`ux_similarity_grouping_active`** é um índice único **parcial** (só sobre as linhas ACTIVE) da
  família. Duas respostas ativas ao mesmo tempo não são um erro de código a evitar: são um `INSERT`
  que o banco recusa.
- **`publish()` é condicional** (só muda a linha que ainda está BUILDING) e devolve quantas linhas
  mudou. Duas publicações concorrentes do mesmo agrupamento: a primeira ganha, a segunda recebe zero
  e o chamador encerra a execução como FINISHED_WITH_ERRORS em vez de publicar duas vezes.
- **A escrita longa e a publicação são transações distintas** — `build()` normal, `publish()` em
  `REQUIRES_NEW` e curta. Um build que morre no meio deixa lixo BUILDING, que ninguém lê e que
  `findByStatusAndComputedAtBefore` encontra por idade.

Consequência direta: **o ACTIVE anterior permanece apresentável durante a recomputação inteira**. A
tela mostra a resposta antiga *e* o aviso de que uma análise está em curso — as duas coisas ao mesmo
tempo, que é o que `aRecomputationInFlightKeepsThePreviousAnswerOnScreen` prende.

## VIII.185 O que o resultado guarda, e o que ele deliberadamente não guarda

`similarity_group_member` guarda o id público da mídia, o veredito, a razão e a posição. **Não**
guarda nome, caminho, tamanho nem miniatura, e **não tem FK para `catalog_file`** — as duas ausências
são decisões, não esquecimento:

- **Sem cópia dos dados do arquivo:** eles pertencem ao catálogo e são lidos na hora de renderizar.
  Um arquivo renomeado ou movido depois da publicação aparece onde está agora; um resultado que
  carregasse cópias seria um segundo catálogo envelhecendo em silêncio.
- **Sem FK:** um arquivo que saiu do catálogo não pode apagar linhas de um resultado histórico por
  cascata, nem impedir a limpeza do catálogo por restrição. Ele volta como membro **sem arquivo**,
  marcado como não acionável.

`actionable` é a decisão pronta que a regra front×back exige: a tela nunca combina lifecycle com
"existe?" para inferir se pode apagar — o back-end responde. Um membro cujo arquivo saiu do ACTIVE
(MISSING, DELETED) ou sumiu do catálogo continua visível, como história do grupo, e não oferece ação.

## VIII.186 V4: a API para de calcular

`GET /api/duplicates/similar-photos` e `/similar-videos` respondem:

| Situação | Resposta |
| --- | --- |
| há resultado publicado | `200` com a página, e `outdated` dizendo se a biblioteca andou desde então |
| não há | `202 Accepted` + `Location` da execução, com a análise enfileirada |

Nunca mais um `GET` que agrupa a biblioteca inteira dentro do request. E o `202` é **RF-1 aprovado**
na prática: a tela do Nimbus não consome esses endpoints, então o impacto é sobre consumidor externo,
que passa a ter uma execução para acompanhar em vez de um timeout.

Um resultado desatualizado é **devolvido**, não retido: ele é uma afirmação verdadeira sobre os
arquivos que examinou, e o campo diz que a biblioteca mudou. Reter deixaria o consumidor sem nada
tendo uma resposta utilizável no banco.

## VIII.187 Concorrência: o comportamento histórico, preservado sem runner

O par de runners removido dava, na prática, uma execução de foto e uma de vídeo por vez. O
`CategoryConcurrency` do Worker é um semáforo **por `ExecutionType`** — não um global — então
`SIMILARITY_PHOTO` e `SIMILARITY_VIDEO` com limite 1 cada reproduzem exatamente aquilo: as duas podem
rodar em paralelo entre si, nenhuma consigo mesma. O que mudou é que a exclusão passou a ser do
banco, e portanto vale mesmo com dois processos.
## VIII.188 Reconciliação da 4.2.7, feita antes de qualquer alteração de código

Confronto entre o enunciado da fatia, o plano registrado (VIII.94, VIII.95, VIII.96, VIII.107), o
fechamento real da 4.2.6 e o código de hoje.

### O que o código realmente faz

Três caminhos, e o terceiro é o que a fatia existe para desfazer:

| Caminho | Onde roda | O que produz |
| --- | --- | --- |
| `POST /api/organization/preview` (REST) | **App**, dentro da request | plano no corpo, teto de 10 000 |
| `POST /api/organization/preview/export` (REST) | **App**, dentro da request | recalcula tudo e streama ZIP |
| `POST /app/organization/preview` (MVC) | **App**, `@Async` | `Execution` RUNNING escrita à mão + `OrganizationExecutor` em dry-run + plano num `Map` |

O terceiro é a razão da exceção. `OrganizationService.previewAsync` → `OrganizationAsyncRunner` →
`OrganizationExecutor`, e o executor **é** quem detém `LibraryFileMutations`. A App compõe a classe
que pode mover arquivo, e é isso que o ArchUnit registra:

> `OrganizationService` — *"the preview runs in the application and composes the executor to build
> its plan"* — 4.2.7

Confirmado também o que **não** é razão: `organizationService.preview` (o caminho REST) chama
`organizationPlanner.preview` direto, e o planner não detém port de mutação nenhum. Os `Files.exists`
do `OrganizationConflictDetector` são **leitura**, e leitura nunca esteve sob os ports. Portanto o
2 → 1 não depende de RF-2 — mas RF-2 continua atribuída a esta fatia por outro motivo, o do cálculo
pesado dentro da request.

### Estado autoritativo em memória — a busca, item a item

| Procurado | Encontrado |
| --- | --- |
| Map/cache/lista de previews | **`OrganizationPlanStore`** — `LinkedHashMap` LRU de 5 entradas, `Map<Long, OrganizationPlan>` |
| preview perdido em restart | **sim** — o `Map` morre com o processo; a tela responde "plano não encontrado" |
| preview criado pela App e executado pela App | **sim** — `runPreview` roda o executor no processo da App |
| dependência de objeto Java entre preview e apply | **não** — e é decisão registrada: o `execute` recalcula |
| TTL só em memória | **sim** — a "expiração" é a eviction de LRU aos 5 planos, nada mais |
| segundo motor | **não** — `OrganizationPlanner` é o único; o que difere é onde roda |
| `Files.*` fora dos ports | **não** — só leitura (`Files.exists`) no detector de conflito |
| synchronized/AtomicBoolean como coordenação | **não** — `Collections.synchronizedMap` protege a estrutura, não coordena nada distribuído |
| REST e MVC com motores diferentes | **não** — mesmo planner, processos diferentes |
| mutação sem Execution | **não** |

### V6, V17, V18, V19 — o que cada um é, no código

| Item | O que é | Como fecha nesta fatia |
| --- | --- | --- |
| **V6** | o `OrganizationPlanStore` em si | a classe **sai**; o plano passa a ser tabela |
| **V17** | `OrganizationExecutor` grava o plano no store **também na execução real**, onde ninguém lê | a gravação passa a ser condicional ao preview; a execução real não escreve plano |
| **V18** | o Javadoc do store diz "centenas de milhares" e o `MAX_LIMIT` corta em 100 000 | o Javadoc sai junto com a classe |
| **V19** | 5 planos × 100 000 itens × ~344 B ≈ **171 MB** de heap retido numa App com `-Xmx1g` | zero itens em heap: a tela pagina do banco |

### Duas decisões que o enunciado levanta e o documento já tinha respondido

**Preview × apply.** O enunciado diz que o sistema não pode mostrar um plano A e executar um plano B
silenciosamente. VIII.96 registra o contrato oposto ao que uma leitura literal sugeriria — *"o
execute recalcula e **não** lê o plano; divergência entre os dois é consequência normal da biblioteca
ter mudado, não corrupção"* — e o classifica como **regra funcional existente (B), preservada**.

Decisão tomada e confirmada: **manter VIII.96 e atacar o "silenciosamente"**. O plano passa a guardar
`catalog_signature`, e a tela **avisa** que o catálogo mudou desde que aquele plano foi produzido. O
que o enunciado proíbe é a divergência muda; o que ele não pede é transformar o `execute` em
aplicador do plano — o que tornaria o plano pré-condição do execute, exatamente o que VIII.96 proíbe.

**TTL.** VIII.94 dizia que expiração por tempo era desnecessária; **VIII.107 revisou** e é o que vale:
o preview é efêmero, tem `expires_at` próprio e retenção mais curta que a da execução, com default de
**12 horas** classificado explicitamente como *tuning*, não constante arquitetural. É essa a regra
implementada — não uma inventada aqui.

### RF-2 — aprovada integralmente

Estava registrada como **C — proposta aguardando aprovação**, atribuída a esta fatia. Aprovada agora,
inteira: o `POST /preview` passa a enfileirar e responder `202` com a referência, um `GET` novo lê o
plano publicado paginado, e o export lê o plano persistido em vez de recalcular. O efeito é o mesmo
que a 4.2.6 obteve com V4 — nenhum endpoint volta a agrupar ou planejar a biblioteca inteira dentro
de uma request.

### O critério da fatia

`TEMPORARY_CONSUMERS` de **2 → 1**, restando apenas `LibraryCatalogCleanupService` (4.2.8), e não por
apagar um nome da lista: a razão desaparece quando a App deixa de compor o executor.
## VIII.189 O preview sai do heap: como ficou

O desenho de VIII.94, com a revisão de TTL de VIII.107, implementado como estava escrito.

| Papel | Classe | Onde roda |
| --- | --- | --- |
| Pedir | `OrganizationPreviewLauncher` | App |
| Executar | `OrganizationPreviewJobHandler` → `OrganizationPreviewJob` | Worker |
| Gravar | `OrganizationPlanWriter` — `build()` longa (BUILDING) + `publish()` curta (`REQUIRES_NEW`) | Worker |
| Ler | `OrganizationPlanReader` → `StoredPlanPage` | App |
| Expirar | `OrganizationPlanSweeper` | App |

`OrganizationService` ficou com validar, enfileirar e ler. Não computa plano, não guarda plano, não
escreve linha de execução à mão e não alcança — por caminho nenhum — a classe que move arquivo.

### Um tipo de execução novo, e por quê

`ORGANIZATION_PREVIEW` não é enfeite: o preview e a organização respondem **diferente a todas as
perguntas que a fila faz**.

| | `ORGANIZATION` | `ORGANIZATION_PREVIEW` |
| --- | --- | --- |
| `resumable()` | **não** — metade dos arquivos já se moveu quando alguém percebe que parou | **sim** — só escreve linhas invisíveis; a segunda tentativa é indistinguível da primeira |
| Ownership | obrigatória — mover sem ela é escrever numa biblioteca que talvez já não seja sua | nenhuma — não escreve na biblioteca |
| Slot de concorrência | 1 | 1, **próprio** — um preview não disputa a vaga de uma organização real |

Um `executeFlag` no mesmo tipo não conseguiria dar duas respostas para `resumable()`, que a fila lê
**antes de reivindicar**.

### Publicação: nada parcial, nunca

```
INSERT plano BUILDING → grava itens em lote → dry run item a item → UPDATE condicional para READY
```

- `findReadable` filtra `status = READY` **e** `expires_at > agora`. As duas condições na query, não
  no Java: são a mesma pergunta — "isto ainda é algo para olhar?" — e responder metade em código
  deixaria a outra metade para um chamador que pode esquecer.
- `publish` é condicional (`... AND status = BUILDING`) e devolve quantas linhas mudou. Duas
  publicações do mesmo plano: a primeira ganha, a segunda recebe `0` e **não sobrescreve** o que
  alguém já pode estar lendo.
- Um preview que morre no meio deixa linhas que ninguém lê. Não há regra especial para elas: expiram
  como qualquer outra.

### O TTL é uma coluna

`expires_at` é carimbado **quando o plano nasce**, não quando publica — assim uma execução que morre
antes de publicar deixa uma linha que a varredura de sempre recolhe. O default é 12 horas, com
`nimbus-file-manager.organization.plan.ttl-hours` para mudar; o valor é *tuning*, e o que é
arquitetural é a validade ser decidida por estado gravado. Um restart não muda nada: a resposta vem
da mesma coluna.

O `OrganizationPlanSweeper` apaga plano vencido **sem tocar na execução**. É a distinção de VIII.107
levada a sério: "foi pedido um preview" é histórico e fica na tela de execuções; o plano é artefato
para olhar e vai embora antes.

### V6, V17, V18, V19

| Item | Como fechou |
| --- | --- |
| **V6** | `OrganizationPlanStore` **apagada**, com `OrganizationAsyncRunner` junto |
| **V17** | o executor recebe um `Consumer<OrganizationPlan>`; a execução real não passa nenhum e portanto **não escreve plano** |
| **V18** | o Javadoc que divergia do `MAX_LIMIT` saiu com a classe |
| **V19** | zero itens em heap: a tela pagina do banco, e o export streama por páginas de 500 |

### RF-2, aprovada e implementada

| Antes | Agora |
| --- | --- |
| `POST /preview` → 200 com o plano no corpo, teto de 10 000 | `POST /preview` → **202** + a execução; `GET /preview/{id}` → página do plano publicado |
| `POST /preview/export` recalculava a biblioteca dentro da request | `GET /preview/{id}/export` streama **o plano publicado**, lido em páginas |

Sumiu o teto de 10 000: ele existia porque o plano vinha inteiro no corpo. Paginado, o tamanho do
plano deixou de ser motivo para recusar um pedido. E nenhum endpoint volta a planejar a biblioteca
dentro de uma request — a mesma correção que V4 fez para a similaridade na 4.2.6.

### Preview × apply: a divergência deixa de ser muda

VIII.96 mantido: o `execute` recalcula e **não** lê o plano. O que mudou é que a tela avisa. O plano
grava `catalog_signature` — contagem de arquivos ACTIVE sob a origem e o `updated_at` mais recente
das **localizações**, que é o que muda quando um arquivo se move — e o leitor compara com a
assinatura de agora.

Um plano sem assinatura **não** alega que algo mudou: plano antigo não é prova de movimento, e avisar
em todos ensinaria o usuário a ignorar o aviso.

### Confirmação dupla

Continua sem dedup, por decisão registrada: duas confirmações são duas coisas que alguém pediu. O que
impede duas execuções destrutivas equivalentes não é um `synchronized` — é o banco, em duas camadas:
o **lock de caminho** (advisory lock do PostgreSQL) impede que rodem ao mesmo tempo sobre as mesmas
pastas, e o **recálculo** faz a segunda encontrar uma biblioteca já organizada, onde não há o que
mover. A garantia atravessa processos porque nenhuma das duas está em memória.

## VIII.190 Descoberta da 4.2.7 — V20, e por que ela não virou exceção nova

Tirar `OrganizationService` da lista revelou uma segunda razão pela qual a App alcançava um port,
que o nome na lista escondia:

> **V20** — `OrganizationReconcileService` acumulava a varredura (leitura) e a aplicação (mutação) na
> mesma classe. A tela chama `reconcile`, que só lê; mas a classe **detinha** `ReconcileApplier`, e a
> capacidade é concedida por injeção. A App tinha a capacidade de reescrever o catálogo para
> responder a uma pergunta que não reescreve nada.

Classificado como **pertencente a esta fatia**: é a mesma exceção, pela mesma classe de motivo, e
deixá-lo teria significado trocar um nome na lista por outro.

Fechado por decomposição, não por exceção: `OrganizationReconcileApply` nasceu com o applier e o
lock, `OrganizationReconcileService` ficou com a varredura e nada mais. O `ReconcileJobHandler` (que
é worker) e o agendador passam pela classe nova; a tela continua chamando a de leitura.

**`TEMPORARY_CONSUMERS`: 2 → 1.** Resta `LibraryCatalogCleanupService`, da 4.2.8.
## VIII.191 Reconciliação da 4.2.8, feita antes de qualquer alteração de código

### O call path real, ponta a ponta

Há **um** fluxo, não sete. O produto não tem "adicionar biblioteca", "selecionar biblioteca" nem
"esquecer biblioteca" como operações separadas: tem **uma configuração** — `WATCH_FOLDER` — e trocá-la
é o switch. Esquecer a anterior é uma consequência da troca, não um comando próprio.

```
POST /app/settings/parameter (MVC)
  → SettingsParameterWebController: exige confirmLibraryChange, valida pasta nova e pasta de quarentena
  → LibrarySwitchService.switchLibrary  @Async, thread da App
      └ openMaintenanceWindow()                     ← trava global: nenhum worker claima nada
        ├ inventoryWatchService.pause()
        ├ waitForCancellation()                     ← requestAllCancellations + poll de 200 ms, teto de 120 s
        ├ cleanupService.clear(oldFolder)           ← A MUTAÇÃO, na App
        │   ├ catalogMutations.forgetLibrary(path)  ← DELETE em catalog_file
        │   └ Files.walk + deleteIfExists           ← cache de thumbnails (workspace)
        ├ appSettingService.update(WATCH_FOLDER, new)
        └ inventoryWatchService.reconfigureAndInventory()
```

### O que a busca encontrou

| Procurado | Encontrado |
| --- | --- |
| mutação de catálogo iniciada pela App | **sim** — `forgetLibrary`, e é a razão da exceção |
| `Files.*` fora dos ports | **sim, e legítimo** — o cache de thumbnails é artefato do workspace, já classificado em `WORKSPACE_AND_INFRASTRUCTURE_WRITERS`; nenhum arquivo do usuário é tocado |
| cleanup síncrono em request | **não** — é `@Async`; a tela redireciona com "troca iniciada" |
| estado autoritativo em memória | **não** — o que decide é `WATCH_FOLDER`, que é linha de banco |
| lock global desnecessário | **sim** — ver abaixo |
| segundo motor / fallback local | não |
| identidade de library numa intenção antiga | **não existe intenção durável nenhuma**: a troca inteira é uma thread |
| possibilidade de apagar catálogo da biblioteca errada | **sim, sob crash** — ver abaixo |

### As duas fragilidades reais, e nenhuma delas é "a lista do ArchUnit"

**A troca não é uma intenção durável.** Ela é uma thread `@Async` segurando uma sessão JDBC. Se a App
morre entre o `forgetLibrary` e o `update(WATCH_FOLDER)`, o catálogo da biblioteca A foi apagado e a
configuração continua apontando para A. Nada retoma: no restart o watcher reinventaria A do zero. Não
há perda de arquivo do usuário — só de trabalho — mas também não há quem termine o que começou.

**O drain é global, e é o único que existe.** `openMaintenanceWindow()` tem **um** chamador em todo o
código: este. Enquanto ela está aberta, `backgroundWorkPaused()` faz **todo** worker parar de claimar
**qualquer** execução, inclusive as que não têm relação nenhuma com a biblioteca trocada. E o teto de
120 s existe porque a espera é um laço em memória.

### O que substitui o drain global

Nada precisa ser inventado. **Toda execução claimada já adquire lock de caminho** sobre seu
`sourcePath`/`targetPath` (`ExecutionDispatcher.runClaimed` → `acquireFor`), e quem não consegue é
**devolvido à fila com o orçamento de tentativas intacto** — espera durável, entre processos, sem
laço. Uma troca que declare `source = biblioteca antiga` e `target = biblioteca nova` trava exatamente
as duas árvores e nada além delas, que é a classificação por recurso no lugar da parada geral.

A política de conflito **não muda**: continua cancelamento cooperativo (`requestCancelOfEverything`,
que é durável), agora antes de enfileirar em vez de dentro de um laço de espera.

### Esquecer ≠ apagar arquivo do usuário

Auditado no schema, não presumido. `forgetLibrary` é `DELETE FROM catalog_file` por prefixo de
caminho; o que vai junto é decidido por chave estrangeira:

| Tabela | Ação | Consequência |
| --- | --- | --- |
| `catalog_file_location`, `media_metadata`, `photo`, `video`, `media_fingerprint`, `fingerprint_failure`, `duplicate_file_exclusion` | **CASCADE** | somem com o arquivo catalogado |
| `movement` | **SET NULL** | o histórico permanece, desanexado |
| `execution` | sem FK | histórico de execuções intacto |
| `similarity_group_member` | **sem FK, por decisão da 4.2.6** | o resultado publicado permanece; a leitura mostra o membro como não acionável |
| `organization_plan` | FK para `execution` | intacto; expira pelo seu próprio TTL |

**Nenhum arquivo do usuário é apagado.** A única escrita em disco é o cache de thumbnails, dentro do
workspace, sob a guarda do `ClusterProtection`. Isso é regime existente e não muda nesta fatia.

### A decomposição, e por que ela é esta

`LibraryCatalogCleanupService` **não é o problema** — é o sintoma. O problema é que a troca inteira
roda na App. Mover só a limpeza deixaria a App decidindo *quando* ela acontece, e a exceção
reapareceria com outro nome.

A transação lógica real, separada pelo que cada passo é:

| Passo | Natureza | Onde fica |
| --- | --- | --- |
| validar pastas | leitura | App, antes de enfileirar |
| cancelar o que está rodando | sinal durável | App, antes de enfileirar |
| **esquecer o catálogo da anterior** | **mutação** | **Worker** |
| limpar cache de thumbnails | artefato do workspace | Worker, no mesmo passo |
| gravar `WATCH_FOLDER` | configuração | Worker, depois de esquecer — é o que ordena os dois |
| reconfigurar watcher e inventariar | comportamento da App | App, ao notar que a configuração mudou |

A ordem importa e é o motivo de os três primeiros passos serem uma execução só: se a configuração
virasse B antes do esquecimento de A, a tela mostraria A e B misturados até o worker chegar.

### Identidade da biblioteca (o cenário T0–T3)

O payload carrega `oldLibrary` e `newLibrary` **explicitamente**, e o handler esquece o caminho que o
payload nomeia — nunca "a biblioteca atual". Uma execução de troca A→B que acorde depois de outra
troca já ter levado para C esquece **A**, que era a intenção, e grava **B**; a troca seguinte esquece
B. Nenhum passo lê `WATCH_FOLDER` para decidir o que apagar. É por isso que o cenário do enunciado não
tem como acontecer: não existe caminho em que "a atual" seja consultada para uma intenção antiga.

### Critério

`TEMPORARY_CONSUMERS` **1 → 0**, e com ele some o mecanismo: a lista existia só para esta migração, e
`openMaintenanceWindow`/`backgroundWorkPaused` existiam só para esta troca.
## VIII.192 A troca de biblioteca vira uma linha, e a Fase 4 fecha

| Papel | Classe | Onde roda |
| --- | --- | --- |
| Pedir | `LibrarySwitchLauncher` | App |
| Executar | `LibrarySwitchJobHandler` | Worker |
| Esquecer o catálogo | `LibraryCatalogCleanupService` | Worker, alcançado só pelo handler |
| Adotar a nova | `AppSettingService.update(WATCH_FOLDER, …)` | Worker, depois de esquecer |
| Convergir o watcher | `InventoryWatchService` | App, ao notar que a configuração mudou |

### O que a App faz, e o que ela deixou de fazer

Fica: recusar uma pasta que não é pasta enquanto alguém está olhando a tela, e pedir que o que está
rodando pare. Sai: a thread `@Async`, a sessão JDBC segurada, o `DELETE` no catálogo, a gravação da
configuração e o laço de espera de 120 s.

`SettingsParameterWebController` continua devolvendo o mesmo redirect com a mesma mensagem. O que
mudou por baixo é que agora existe uma execução para acompanhar em vez de uma thread para torcer.

### Identidade: o que a linha diz é o que o worker faz

O handler esquece `claimed.sourcePath()` e adota `claimed.targetPath()` — **as colunas da própria
linha**, que são também o que o dispatcher travou antes de chamá-lo. Em ponto nenhum ele lê
`WATCH_FOLDER` para decidir o que apagar, e há teste que verifica exatamente essa ausência.

O cenário T0–T3 do enunciado, portanto:

| | |
| --- | --- |
| T0 | linha criada: "esquecer A, adotar B" |
| T1 | usuário troca de novo; agora a biblioteca é C |
| T2 | worker reivindica a linha antiga |
| T3 | esquece **A** e grava **B** — a intenção de quem pediu, não o estado de agora |

Um switch A→B que rode depois de um B→C é um retrocesso pedido por alguém, não um acidente: o
segundo switch existe como linha própria e roda na sua vez.

### Idempotência, e o que sobrevive a cada fronteira

Todo passo é repetível, e é isso que torna o handler `resumable()`:

| Morre em | Ao retomar |
| --- | --- |
| antes de enfileirar | nada aconteceu; a configuração ainda nomeia a antiga |
| depois de enfileirar | a linha está PENDING; outro worker a pega, com os dois caminhos escritos |
| durante o esquecimento | o `DELETE` é uma transação: aconteceu ou não; a nova tentativa apaga o resto |
| entre esquecer e gravar | a nova tentativa esquece de novo (0 linhas) e grava a configuração |
| depois de gravar | a nova tentativa repete os dois, com o mesmo resultado |
| worker morre segurando | o lease expira, outro reivindica |
| App reinicia | irrelevante: a App não participa da execução |
| PostgreSQL reinicia | a linha continua lá; os locks são de sessão e somem com ela |

### O drain global saiu, e nada o substituiu — porque nada precisava

`openMaintenanceWindow()` tinha **um** chamador em todo o código: a troca. Enquanto aberta, ela
parava **todo** worker de reivindicar **qualquer** execução, inclusive as que nada tinham a ver com a
biblioteca trocada.

O que protege agora é o que já protegia todas as outras execuções: **o lock de caminho**. Uma troca
declara as duas bibliotecas como origem e destino, o dispatcher trava as duas árvores antes de
chamar o handler, e quem não consegue é devolvido à fila com o orçamento de tentativas intacto.
Conflito por recurso, espera durável, e trabalho sobre qualquer outra pasta continua correndo.

Com o último chamador, saíram também `MaintenanceWindow`, `backgroundWorkPaused()` e
`ExecutionLockKeys` — todos existiam só para isto.

**A política de conflito não mudou**: continua cancelamento cooperativo, agora pedido antes de
enfileirar em vez de dentro de um laço.

### O watcher passou a seguir a configuração

O worker grava `WATCH_FOLDER` em outro processo, então o watcher da App precisa notar. Ele já tinha
um laço de poll; agora esse laço compara a pasta configurada com a que está observando e reconfigura
quando divergem. Isso fecha de passagem uma lacuna anterior a esta fatia: a pasta só era relida
quando alguém chamava `reconfigure` de uma thread de request, e uma configuração mudada por qualquer
outro caminho passava despercebida até o restart.

### Esquecer continua não sendo apagar

Auditado e agora provado em PostgreSQL real (`ForgetLibraryIntegrationTest`): a biblioteca esquecida
continua inteira em disco, byte a byte; o histórico de movimentos permanece, desanexado pela chave
estrangeira; a execução que o produziu permanece; e uma pasta vizinha cujo nome apenas começa igual
(`media` × `media-old`) não é tocada.

## VIII.193 `TEMPORARY_CONSUMERS` = 0, e a lista deixa de existir

A última exceção não foi apagada da lista: a razão dela desapareceu. `LibraryCatalogCleanupService`
continua existindo e continua detendo `CatalogMutations` — só que agora é alcançada exclusivamente
por um `ExecutionJobHandler`, que é o que sempre tornou a capacidade legítima. Ela saiu da lista
temporária e entrou na permanente, `WORKER_CONSUMERS`.

E então a lista foi embora com o mecanismo: `TEMPORARY_CONSUMERS`, `TemporaryMutationPortConsumer`,
o `isTemporary` que abria buraco na travessia de P3 e o teste que cobrava prazo de cada entrada. O
que sobrou no lugar é uma regra sem exceção e um teste que diz isso positivamente —
`noCapabilityIsStillBeingMigrated`: todo detentor de um port de mutação é trabalho do worker ou a
própria implementação do port. Não há lista onde escrever um nome novo, que é o que torna reintroduzir
a forma removida um build vermelho em vez de uma linha de allowlist.

**Fase 4 fechada:** P1–P5 verdes, zero exceções temporárias, zero mecanismos temporários.
## VIII.194 Fechamento da Fase 4, e o que o plano tinha de errado

Reconciliação do plano histórico com o código, feita ao fechar a 4.2.8. Esta seção **não** reescreve
o que veio antes: o documento é um diário, e um diário que soubesse o final desde o começo não teria
servido para nada. O que ela faz é dizer, de um lugar só, o que sobreviveu.

### O que o plano acertou

A ordem. Migrar primeiro quem move arquivo, depois quem só lê pesado, foi certo — e a razão ficou
visível na 4.2.6: a similaridade não podia ir para o Worker antes de alguém decidir onde o resultado
ia morar, e essa decisão só ficou fácil depois que cinco fatias tinham estabelecido o padrão
(payload versionado, launcher, handler, publicação atômica).

O enforcement por port com lista temporária também acertou. Um `@Deprecated` ou um comentário teria
apodrecido; uma lista que **quebra o build quando um nome novo aparece** obrigou cada fatia a
justificar sua exceção e a marcar a data dela.

### O que o plano errou

**Subestimou o último passo.** "Passo 9 — limpeza" virou cinco fatias, porque tirar o alcance da App
a um port não é remover uma linha: é descobrir *por que* ela estava lá. Duas vezes a razão declarada
era só a metade visível — na 4.2.7 o preview escondia o reconcile (V20), e na 4.2.8 a limpeza de
catálogo escondia a janela de manutenção global.

**Chamou de "Fase 5" duas coisas diferentes.** A lista de escritores (organização, undo, conversão,
dedup, quarentena) foi inteiramente absorvida pela Fase 4 — ela era a Fase 4, com outro nome. O que
sobrou com esse rótulo são os sete workloads pesados de VIII.8, dos quais três também já saíram:
as duas similaridades (4.2.6) e a purga de catálogo (4.2.5).

**Previu como pré-requisito o que virou entrega.** VIII.8 recomendava "tratar a persistência dos
grupos como pré-requisito da Fase 5" e alertava que o segundo motor da similaridade sobreviveria a
qualquer migração. Ambos foram resolvidos dentro da 4.2.6, então a Fase 5 começa sem esse débito.

### O que resta, contado uma vez

| # | Workload | Estado |
| --- | --- | --- |
| 15, 16 | Similaridades | **entregue** — 4.2.6 |
| 22 | Purga de catálogo ausente | **entregue** — 4.2.5 |
| 17, 18 | Backlogs de fingerprint (foto e vídeo) | **entregue** — 5.1 |
| 19 | Rebuild de metadata | pendente |
| 20 | Rebuild de localização | pendente |
| 21 | Dataset geográfico | pendente |

E três runners que **não** são dívida: update, instalação de ferramentas e backup permanecem na App
por decisão de domínio (§19 do documento normativo). O backup em particular não deve ser migrado para
zerar a contagem de `@Async` — ele coordena o cluster que a App supervisiona, e é reavaliado no
fechamento arquitetural, não agora.

### Onde procurar o que

| Pergunta | Documento |
| --- | --- |
| Por que a arquitetura é assim | `a8-processamento-em-worker-separado.md`, corpo |
| O que existe hoje | idem, seção *Estado vigente* |
| Como se chegou aqui, fatia a fatia | este arquivo |
| O que ainda falta | a tabela acima, e *Estado vigente* |

## VIII.195 Os dois backlogs de fingerprint viram execução, e o segundo motor sai

Primeira fatia da Fase 5. Reconciliação feita antes de tocar em código, como nas anteriores.

### O que a busca encontrou: o backlog já rodava nos dois processos

O drain não era "um runner na App". Ele tinha **três entradas**, e as três continuavam vivas:

| Entrada | Processo | O que disparava |
| --- | --- | --- |
| `PhashBacklogStartup` / `VideoFingerprintBacklogStartup` | App | subida da aplicação |
| botões da tela Duplicados | App | clique do usuário |
| `FingerprintBacklogResumer`, chamado pelo `InventoryScanRunner` | **Worker** | fim de cada inventário |

A terceira é a que importa: desde a Fase 4.1 o inventário roda no Worker, então o drain que ele
dispara roda no Worker também — enquanto os outros dois rodavam na App. O que impedia dois drains
simultâneos era um `AtomicBoolean` por runner, isto é, **por JVM**. Dois processos, dois campos, e
nenhum dos dois enxergava o outro: o guarda existia e não guardava nada.

Não era um risco teórico. Um inventário terminando enquanto a tela pedia um rebuild dava dois drains
sobre a mesma tabela, cada um lendo "o que está faltando" antes de o outro escrever.

As três entradas viraram a mesma chamada — `launch`, que enfileira ou entra na linha que já existe. O
`FingerprintBacklogResumer` saiu junto: ele existia para dar ao inventário uma dependência estreita
em vez de duas, e depois da fatia ele repassava uma chamada a uma dependência já estreita. Retomar e
pedir deixaram de ser coisas diferentes.

### `fingerprint_job_run`: o segundo histórico

Havia uma tabela inteira registrando execuções de fingerprint — `started_at`, `finished_at`,
`total_at_start`, `processed`, `failed`, `message` — que é, coluna a coluna, o que `execution` já
guarda. Fora do próprio runner **nada a lia**: nem tela, nem API, nem relatório.

Ela sai. Mas sai levando o que tem: a migration `V24` copia cada linha para `execution` (mapeando
`kind` para `FINGERPRINT_PHOTO`/`FINGERPRINT_VIDEO` e o status para o vocabulário da fila) **antes**
do `DROP TABLE`. É a regra de persistência do `AGENTS.md` aplicada ao caso mais fácil de errar:
banco de teste vazio não reclama de histórico apagado, e o de quem usa o produto, sim.

### O que a tela perguntava a um campo, e agora pergunta à linha

"Está rodando?" e "falta quanto?" eram campos do runner. Numa arquitetura de dois processos, essa
pergunta feita pela App sobre trabalho que acontece no Worker só tinha uma resposta possível: não.
`FingerprintRunReader` passa a derivar as duas da própria `execution` — presença de linha ativa, e
tempo decorrido contra a fração alcançada. Efeito colateral que é o ponto: a barra sobrevive a um
restart de qualquer um dos lados e diz a mesma coisa em duas abas abertas.

### Quatro decisões da fatia

**`rebuild` faz parte da identidade do pedido, não é detalhe dele.** A chave de dedup é
`FINGERPRINT_PHOTO:drain` ou `FINGERPRINT_PHOTO:rebuild`. Dois "termine o que falta" são o mesmo
pedido — o backlog não tem argumentos, é uma consulta. Já colapsar um rebuild sobre um drain
pendente responderia uma pergunta que ninguém fez.

**Backlog vazio não vira linha.** A primeira versão enfileirava sempre, e o efeito apareceu nos
testes de integração antes de aparecer em produção: uma execução por subida e uma por inventário,
todas encontrando zero arquivos e terminando. Histórico é do usuário; enchê-lo de runs que não
fizeram nada é ruído com custo. O rebuild é isento — ele *cria* o trabalho sobre o qual pergunta.

**Ceder o lugar agora termina uma linha.** Quando o handler é reivindicado com um inventário ou uma
conversão ativa, ele fecha como `REJECTED` em vez de deixar uma thread tentando de novo. Quem repõe
o pedido é o inventário, ao terminar — que é exatamente o que ele já fazia.

**Payload de outro schema é recusado, não lido pela metade.** Ler errado um único booleano aqui
descartaria todos os fingerprints do catálogo.

### Resumível sem checkpoint

O handler declara `resumable()`, e não guarda posição. O trabalho é "os arquivos deste tipo que não
têm fingerprint": uma segunda tentativa pergunta de novo e encontra o que a primeira não alcançou.
Não há o que lembrar porque o trabalho se descreve sozinho — o oposto de uma organização, que sem o
plano não sabe o que ia mover.

### Um pool ficou sem ninguém

O `AsyncConfig` tinha três pools, e o de *análise visual* existia para exatamente estes dois
runners — os de similaridade, que o dividiam com eles, já haviam saído na 4.2.6. Com os dois últimos
submissores virando handlers do Worker, sobrou um pool de duas threads que ninguém alimenta: um
orçamento de threads gasto em nada, e um bean que o próximo leitor tentaria entender. Saiu junto,
com sua constante e suas asserções.

### Concorrência: dois tipos, um slot cada

Foto e vídeo são `ExecutionType` distintos com limite 1 cada, e não um tipo com limite 2. Um drain
de vídeo consome processos ffmpeg e disputa com uma conversão; um de foto não. Tipos separados
deixam essa diferença expressa onde ela é lida — na configuração de concorrência — em vez de
escondida num `if` dentro do handler.

## VIII.196 O rebuild de metadados vira execução, e o dry run ganha casa

Segunda fatia da Fase 5. Reconciliação antes do código, como nas anteriores — e ela mudou o
agrupamento previsto (ver *O par que não dava para separar*, abaixo).

### Três entradas, dois processos, e uma sem guarda nenhuma

| Entrada | Processo | Guarda |
| --- | --- | --- |
| `SettingsMetadataWebController` → `MetadataRebuildAsyncRunner` | App | `AtomicBoolean` do runner |
| `POST /api/metadata/rebuild` → `MetadataRebuildService` | App, **síncrono dentro do request** | **nenhuma** |
| `OrganizationMetadataRebuild.beforePlanning` | **Worker**, dois handlers | a execução que o contém |

A segunda linha é o achado. Um POST na API rodava o rebuild inteiro dentro da requisição, sem
consultar o `AtomicBoolean` que a tela usava — nem poderia, porque o guarda era de um caminho, não do
trabalho. Dois rebuilds sobre a mesma pasta, um pela tela e outro pela API, escreviam as mesmas
linhas ao mesmo tempo, e o segundo ainda segurava a conexão HTTP durante o tempo todo.

A terceira não era defeito, e continua não sendo: ali o rebuild é **passo de uma organização**,
dentro da execução que pediu, e não trabalho que alguém pediu por si. O que estava errado era o
Javadoc, que dizia "a aplicação ainda roda antes de um preview" — falso desde a 4.2.7, quando o
preview virou handler. Corrigido.

### O que o dry run é, e por que ele precisou de tabela

O rebuild real não precisa de lugar nenhum: o que a tela mostra são contadores que a `execution` já
tem — candidatos, reconstruídos, ausentes do disco, erros — e uma mensagem que ela já carrega.

O **dry run** é outra coisa. Ele não conta, ele *mostra*: quantos arquivos o corte de "continuar de
onde parou" está escondendo, quantos foram lidos por amostragem, e uma tabela das primeiras datas que
mudariam. Isso é resultado publicado, da mesma família do `organization_plan` da 4.2.7, e vive em
`metadata_rebuild_preview` + `_item`.

**Sem protocolo de publicação, e de propósito.** A leitura só pergunta pelo preview de uma execução
*terminada*, então o próprio status da execução é o que torna as linhas visíveis. Uma tentativa que
morreu no meio deixa linhas que ninguém pede, e a retentativa as substitui. Foi a comparação com a
4.2.7 que deixou isso claro: lá o `BUILDING`/`READY` existe porque o plano é lido *enquanto* a
execução ainda está viva; aqui não é.

### A fonte da data não é gravada traduzida

A linha do preview guarda o `DateSource` como enum, nunca o rótulo. Gravar "nome ✓" seria gravar o
idioma de quem rodou: o Worker não tem requisição atrás de si e portanto não tem idioma, e uma
simulação feita em pt-BR seria lida em pt-BR por quem está com a tela em inglês. Quem traduz é o
leitor, no momento da leitura — que é o mesmo princípio de *Responsabilidades Front-end × Back-end*
aplicado ao tempo, e não só à camada.

Isso partiu o antigo `MetadataRebuildPreview` em dois: `MetadataDateDifference` (o que o serviço acha
e o handler grava, com enums) e o `MetadataRebuildPreview` de sempre (o que a tela recebe, com
rótulos). O template não mudou uma linha.

### O endpoint REST responde 202

`POST /api/metadata/rebuild` deixa de devolver o que fez e passa a devolver **202 com a execução a
acompanhar**, com `Location` apontando para ela — a mesma resposta que os endpoints de similaridade
dão desde a 4.2.6. É mudança de contrato, e consciente: a resposta anterior só era honesta para uma
pasta pequena, e o preço dela era a aplicação lendo arquivos com exiftool enquanto existe um Worker
para exatamente isso.

### `simulate` deixou de ser um `if` dentro do `rebuild`

O serviço tinha um método que, conforme uma flag, ou reconstruía ou simulava, devolvendo o mesmo
record com metade dos campos zerados. Agora são dois métodos com dois retornos, e quem escolhe é o
handler — que é quem sabe o que a linha pediu. O `MetadataRebuildService` também deixou de depender
do `DateSourceLabels`: ele não redige mais nada.

### O par que não dava para separar

A ordem prevista era metadados, localização, dataset. O código mostrou que o **rebuild de localização
é excluído pelo import do dataset geográfico**, que ainda é `@Async` na App: enquanto o dataset não
for execução, essa exclusão não é expressável na fila — o handler no Worker não tem como perguntar
por um `isRunning()` que mora na memória do outro processo. Então a 5.3 leva os dois juntos, pelo
mesmo motivo que a 5.1 levou os dois backlogs.

O rebuild de metadados não tinha esse problema: seu parceiro de exclusão é o inventário, que é
execução desde a 4.1. Por isso ele veio sozinho, e primeiro.

## VIII.197 O par geográfico vira execução, e a Fase 5 fecha

Terceira e última fatia da Fase 5. Reconciliação antes do código, como nas anteriores.

### A premissa da 5.2 confirmou-se, e era maior

A 5.2 previu que rebuild de localização e dataset se excluem. O código mostrou que a exclusão não
cobre só esses dois: os mesmos dois `isRunning()` guardam também *remover dataset*, *desligar
localização* e *limpar cache*. Cinco ações, dois campos, um processo.

E havia uma assimetria que ninguém tinha visto: **o scheduler de atualização automática não
consultava o rebuild**. Ele checava a configuração, o inventário e a disponibilidade do dataset — e
não o rebuild que podia estar em curso. Uma atualização agendada podia começar por cima de uma
resolução em andamento, exatamente o que os botões da tela impediam. Não era um risco teórico: o
timer roda a cada minuto e o rebuild leva horas.

### A exclusão passa a ser um caminho, não um campo

Nada de categoria nova nem de capability nova. Os dois tipos declaram o **mesmo `sourcePath`** — a
pasta `geodata` do workspace — e o `ExecutionDispatcher` já toma advisory lock exatamente sobre os
caminhos que a linha nomeia. Consequências, todas de graça:

- a exclusão é **entre processos**, e não entre campos de uma JVM;
- é **durável**: quem chega segundo é devolvido à fila (`handBack`) com o orçamento de tentativas
  intacto, e roda quando o primeiro solta;
- **nenhum outro workload é afetado**: inventário, conversão e drain de fingerprint trabalham sobre a
  biblioteca, não sob `geodata`;
- a assimetria do scheduler desaparece sem ninguém precisar lembrar dela, porque ele deixou de
  decidir — ele pede.

O que a tela deixou de fazer é tão importante quanto: os botões de rebuild e de atualizar **não
recusam mais** quando o outro está rodando. Enfileiram. Recusar era pedir ao usuário que vigiasse um
momento que a fila sabe esperar sozinha. O que continua recusando é o que não dá para enfileirar e
desfazer — remover o dataset, desligar a localização, limpar o cache — e essa pergunta agora é uma
só, feita à fila (`GeoRunReader.busy()`), em vez de duas feitas a campos.

### §5 não disparou a condição de parada

O protocolo pedido já existia, e é contratual no `BoundarySource`: `fetch` baixa para `*.staged` ao
lado do publicado, `importDataset` roda em transação própria, `commit` publica **só depois** do
import bem-sucedido, e `discard` no `catch` derruba o staging deixando o dataset anterior intacto.
Um restart no meio não publica nada — o mapa de etags que o `commit` consulta é do processo, então
morre com ele, e os `.staged` ficam sem publicar ao lado do dataset que continua servindo. Foi por
isso que `resumable()` pôde ser `true` sem inventar nada: rodar de novo do zero é indistinguível de
ter rodado uma vez.

### `GeoDatasetProgress` deixou de ser a resposta e virou o repórter

Era um singleton que a pipeline escrevia e a tela lia — o que só funciona enquanto os dois são o
mesmo processo. Agora ele escreve **através**, na linha:

| O que era | Onde passou a viver |
| --- | --- |
| `phase` (DOWNLOADING/IMPORTING) | `execution.phase` (SCANNING/PROCESSING) |
| nível sendo processado | argumento da `statusMessage`, como chave i18n |
| % dentro do passo | `current_item_percent`, com o throttle que a coluna já tem |
| níveis concluídos | `files_analyzed` sobre `total_expected` = 3 |
| `lastResult` | **não migrou — já era duplicata**: o `metadata.json` guarda o resultado |
| `lastError` | idem, e a linha de execução também guarda |
| bytes baixados/importados | **saiu da tela** (ver abaixo) |

Os `AtomicLong` que sobraram são rascunho da run em voo, nunca autoridade sobre se existe uma: isso
é o status da linha.

**O `lastResult`/`lastError` do runner eram um segundo histórico.** O `metadataStore` já persiste
`lastError`, e o status vem do mesmo lugar. Removê-los é a mesma regra da 5.1 aplicada de novo.

### Os megabytes saíram da tela, e isso é consequência, não descuido

A tela mostrava "342,1 MB de 1.204,7 MB" e "1.234.567 registros importados até agora". Um contador de
bytes que muda dez vezes por segundo não é coisa que uma linha guarde — persistir isso seria criar
uma segunda tabela de progresso para um número que a §6 diz explicitamente para não criar. O que a
tela mostra agora é o que a linha sabe dizer com honestidade: **qual nível** e **quanto daquele
nível**. O total de registros continua aparecendo ao fim, vindo do `metadata.json`, que é durável.

### Descoberta final da Fase 5

A varredura ampla depois da migração achou um resto: o **`InventoryScanAsyncRunner`**, sem nenhum
chamador de produção desde a Fase 4.1, quando o inventário virou execução. Só o próprio teste o
instanciava. Código morto de uma fatia anterior, removido aqui.

Sobraram três `@Async`, os três previstos, e agora isso é **teste**, não convenção:
`HeavyWorkloadArchitectureTest` quebra o build se aparecer um quarto, se uma tela alcançar um serviço
pesado, ou se um scheduler chamar o trabalho em vez de pedir. A regra do dataset é por **chamada**, e
não por tipo, de propósito: várias telas perguntam ao `OfflineGeoDataset` se ele está instalado e
quanto ocupa — só `downloadAndImport()` é a hora de trabalho.

## VIII.198 Fechamento da Fase 5

A matriz completa, para poder afirmar a frase que a fase existia para poder afirmar: **não resta
workload pesado destinado ao Worker rodando na App.**

### Já migrado

| Workload | Antes | Depois | `ExecutionType` | Handler | Fatia |
| --- | --- | --- | --- | --- | --- |
| Inventário | `InventoryBatchAsyncRunner` | fila | `INVENTORY` | `InventoryJobHandler` | 4.1 |
| Organização | `OrganizationAsyncRunner` | fila | `ORGANIZATION` | `OrganizationJobHandler` | 4.1 |
| Undo | idem | fila | `UNDO` | `OrganizationUndoJobHandler` | 4.1 |
| Reconcile | idem | fila | `RECONCILE` | `ReconcileJobHandler` | 4.1 |
| Conversão | `VideoConversionAsyncRunner` | fila | `CONVERSION` | `ConversionJobHandler` | 4.1 |
| Exclusão de duplicados | `DuplicateDeletionAsyncRunner` | fila | `DEDUP_DELETE` | `DuplicateDeletionJobHandler` | 4.1 |
| Ações do explorador | chamada síncrona no controller | fila | `EXPLORER_*` | `Explorer*JobHandler` | 4.2.4 |
| Quarentena (restore/purge/cleanup) | serviços na App | fila | `QUARANTINE_*` | `Quarantine*JobHandler` | 4.2.5 |
| Purga de catálogo | agendador na App | fila | `CATALOG_PURGE` | `CatalogPurgeJobHandler` | 4.2.5 |
| Similaridade de fotos | `PhotoSimilarityAsyncRunner` | fila | `SIMILARITY_PHOTO` | `PhotoSimilarityJobHandler` | 4.2.6 |
| Similaridade de vídeos | `VideoSimilarityAsyncRunner` | fila | `SIMILARITY_VIDEO` | `VideoSimilarityJobHandler` | 4.2.6 |
| Preview de organização | `OrganizationPlanStore` em memória | fila + `organization_plan` | `ORGANIZATION_PREVIEW` | `OrganizationPreviewJobHandler` | 4.2.7 |
| Troca de biblioteca | `LibrarySwitchService` + janela global | fila | `LIBRARY_SWITCH` | `LibrarySwitchJobHandler` | 4.2.8 |
| Backlog de pHash | `PhashBacklogAsyncRunner` | fila | `FINGERPRINT_PHOTO` | `PhotoFingerprintJobHandler` | 5.1 |
| Backlog de fingerprint de vídeo | `VideoFingerprintBacklogAsyncRunner` | fila | `FINGERPRINT_VIDEO` | `VideoFingerprintJobHandler` | 5.1 |
| Rebuild de metadados | `MetadataRebuildAsyncRunner` + POST síncrono | fila + `metadata_rebuild_preview` | `METADATA_REBUILD` | `MetadataRebuildJobHandler` | 5.2 |
| Rebuild de localização | `LocationRebuildAsyncRunner` | fila | `LOCATION_REBUILD` | `LocationRebuildJobHandler` | 5.3 |
| Dataset geográfico | `GeoDatasetAsyncRunner` + timer | fila | `GEO_DATASET_UPDATE` | `GeoDatasetJobHandler` | 5.3 |

### Permanece na App por decisão

| Runner | Por quê |
| --- | --- |
| `UpdateInstallAsyncRunner` | baixa o instalador e **encerra a própria aplicação**. Um Worker subordinado teria de matar seu supervisor e sobreviver ao jar sendo trocado |
| `ExternalToolInstallAsyncRunner` | instala o ffmpeg que o Worker usa; dependência circular no primeiro uso se fosse do Worker |
| `CatalogBackupAsyncRunner` | `pg_dump`/`pg_restore` contra o cluster que a App supervisiona; o restore derruba conexões, inclusive as do Worker |

Nenhum dos três muta a biblioteca, e nenhum deve ser migrado só para zerar a contagem de `@Async`.
Agora isso é afirmado por teste: `HeavyWorkloadArchitectureTest` aceita exatamente esses três.

### Fora do escopo

| Item | Por quê |
| --- | --- |
| Thumbnail sob demanda | é resposta a uma requisição, medida em milissegundos, com cache próprio. Não é workload de Worker |
| Watcher de pasta | não executa: observa e **enfileira** |
| `GeoDatasetAutoUpdateScheduler`, `CatalogFilePurgeScheduler` e demais timers | não executam: pedem |
| `ProcessingCoordinator`, `ExternalToolGate`, `CategoryConcurrency` | infraestrutura do Worker, não workload |

### Pendência real

Nenhuma. Os sete workloads pesados de VIII.8 saíram — dois na 4.2.6, um na 4.2.5, dois na 5.1, um na
5.2 e um na 5.3 — e a varredura final não achou um oitavo.

O resto encontrado foi **código morto**, não pendência: o `InventoryScanAsyncRunner`, sem chamador de
produção desde a 4.1, removido na 5.3.

### O que a fase custou, em fatias

A Fase 5 foi planejada como "os workloads pesados" e executada em três fatias, agrupadas não pelo
documento e sim pelo que o código mostrou:

1. **5.1** os dois backlogs de fingerprint, juntos porque compartilhavam o mesmo drain;
2. **5.2** o rebuild de metadados, sozinho, porque seu parceiro de exclusão (o inventário) já era
   execução;
3. **5.3** o par geográfico, junto porque a exclusão entre eles não era expressável na fila enquanto
   os dois não fossem execução.

O critério de agrupamento foi sempre o mesmo, e vale registrar porque não estava no plano: **migram
juntos os workloads que se excluem mutuamente**, porque migrar um só deixa metade da exclusão num
campo de memória que o outro processo não enxerga.

## VIII.199 `isLive` aposentado, e a recuperação passa a ter uma política só

Último item arquitetural pendente da antiga Fase 8.

### O problema

`ExecutionCancellationService` mantinha um `Set<Long> running` — as execuções iniciadas *neste*
processo — e um `isLive(id)` sobre ele. A recuperação de startup da App consultava esse conjunto para
não declarar interrompida uma execução viva.

Era memória decidindo ciclo de vida, e o próprio Javadoc já dizia quando deixaria de ser preciso:
*"once every execution is claimed through the queue and holds a lease"*.

### A pergunta, respondida no código

**"Que informação o `isLive` fornece que um lease válido não forneça?"** Nenhuma, e a prova é uma
instrução SQL: o **único** lugar de produção que escreve `status = 'RUNNING'` é o claim, e ele grava
`claimed_by`, `claimed_at` e `lease_until` na mesma instrução. Nenhum código Java atribui
`ExecutionStatus.RUNNING` a uma entidade. Logo:

- toda linha RUNNING tem lease;
- o `lease_until IS NULL` da consulta `UNOWNED` era um ramo inalcançável;
- **`UNOWNED ≡ EXPIRED`**;
- na App o `Set` estava permanentemente vazio — todos os chamadores de `register()` rodam dentro de
  handlers do Worker — então `isLive` já respondia sempre `false` ali.

### O achado que a remoção expôs: duas políticas, não uma redundância

Como as duas consultas nomeiam as mesmas linhas, App e Worker aplicavam **políticas diferentes ao
mesmo conjunto**, e quem subisse primeiro ganhava:

| | Startup da App | Reclaim do Worker |
| --- | --- | --- |
| Resumível | INTERRUPTED | volta para a fila |
| Não resumível | INTERRUPTED | INTERRUPTED **+ RECONCILE enfileirado** |
| Tentativas esgotadas | INTERRUPTED | ERROR, definitivo |

Uma App reiniciando antes do Worker fechava uma organização abandonada como INTERRUPTED **sem
enfileirar reconcile** — a divergência entre disco e catálogo ficava sem reparo, em silêncio. Não era
redundância a remover: era uma segunda política a eliminar.

### A solução

Uma política durável, executada pelos dois papéis no seu próprio start. O `ExecutionReclaim` deixou
de ser `@Profile(WORKER)`; o `StartupExecutionRecoveryListener` da App delega a ele em vez de aplicar
a sua. Rodar duas vezes é seguro por construção: o requeue é um UPDATE condicional sobre a linha que
foi lida, e o reconcile que ele enfileira é deduplicado por pasta.

O `ExecutionReclaim` continua no pacote do worker de propósito: o que ele raciocina é o protocolo de
claim — leases, orçamento de tentativas, o que o handler diz sobre retomar — e trazê-lo para
`execution` traria as regras do worker para o domínio que só guarda a fila.

Saíram junto, por perderem todos os consumidores: `markInterruptedExecutions`,
`interruptWhenOrphaned`, a consulta `UNOWNED`, o método `unownedExecutions()`, e as duas dependências
que o `ExecutionProgressService` mantinha só para eles. O `register()` era no-op — o cache de
cancelamento não pode ter entrada para um id que ainda não começou — e o `unregister()`, que só
despeja essa entrada, virou `forget()`, um nome que diz o que ele faz agora que não há registro
nenhum.

### A prova entre processos

`ExecutionLivenessIntegrationTest`, com PostgreSQL real e **sem** `@Transactional` — dois commits são
exatamente a visibilidade que um segundo processo tem:

- **A reivindica e segura; B pergunta e não vê nada a recuperar.** B não conhece A: nem objeto
  compartilhado, nem conjunto, nem id que alguém lhe tenha dito.
- **O heartbeat mantém a resposta verdadeira**, e a renovação é recusada a quem não é dono — o que
  impede um segundo worker de estender um lease que não tem.
- **O lease expira e a linha vira trabalho abandonado para qualquer um**, sem ninguém avisar.
- **Um processo subindo não perturba o que outro está fazendo**: a lista que a recuperação lê traz o
  lease vencido e não traz o segurado.
- **Um pedido que ninguém tomou não é trabalho abandonado**: nunca teve lease para vencer.

### Portas e gates

Varredura mecânica do mecanismo antigo: `isLive` 0 em produção, `Set<Long> running` 0,
`register`/`unregister` 0, `markInterruptedExecutions` 0, `unownedExecutions`/`UNOWNED` 0. As duas
ocorrências de `isLive` que restam em `src/` são um helper privado do dublê de `SelfWrittenPaths`,
sobre TTL de anúncio de caminho — nome parecido, assunto diferente, e por isso ficaram.

### Classificação final da antiga Fase 8

Encerrada. `AsyncConfig` ficou com um pool e três submissores deliberados; `@Async` e runners
obsoletos saíram nas fatias 5.1 a 5.3; `AtomicBoolean running` de workload migrado não existe;
`isLive` sai aqui. O `inventoryPending` permanece, e não é dívida: é debounce do watcher, nunca foi
ciclo de vida de `Execution`.

## VIII.200 Fechamento do A8

Última seção deste diário. Ele para aqui porque o que ele acompanhava terminou.

### A auditoria final, propriedade a propriedade

| Propriedade | Estado | Como é garantida |
| --- | --- | --- |
| Workload pesado destinado ao Worker rodando na App | **0** | `HeavyWorkloadArchitectureTest` |
| Segundo motor de execução | **0** | varredura mecânica + `V17__spring_batch_tables_leave_the_catalog.sql` |
| Ciclo de vida de `Execution` decidido por memória da App | **0** | `ExecutionLivenessIntegrationTest` + o lease |
| Exceções temporárias P1–P5 | **0** | `MutationBoundaryArchitectureTest` |
| `@Async` na App | **exatamente 3** | `HeavyWorkloadArchitectureTest` |
| Scheduler executando trabalho pesado | **0** | `HeavyWorkloadArchitectureTest` |
| UI alcançando motor pesado | **0** | `HeavyWorkloadArchitectureTest` |
| App alcançando port de mutação | **0** | `MutationBoundaryArchitectureTest` |

Nenhuma delas é convenção: as oito quebram o build.

### O último item

`isLive` — a decisão de vivacidade por um `Set` em memória — foi aposentado, e com ele veio a
descoberta de que App e Worker mantinham **políticas concorrentes de recuperação sobre as mesmas
linhas**. Unificadas: `ExecutionReclaim` é a política única, executada por qualquer processo ao
subir. O detalhe está em VIII.199.

### O que a documentação virou

O A8 deixou de ser documentação ativa. Os papéis agora são:

| Documento | Papel |
| --- | --- |
| `docs/architecture/worker-architecture.md` | **como** a arquitetura funciona hoje — autocontido |
| `docs/adr/0003` a `0008` | **por que** as decisões permanentes foram tomadas |
| `docs/archive/a8-processamento-em-worker-separado.md` | plano e evolução histórica |
| `docs/archive/a8-auditoria-de-aderencia.md` | este arquivo: evidência da execução, fatia a fatia |

Seis ADRs, escolhidos por coesão e não por contagem: fronteira de processo (0003), protocolo durável
de comando (0004), posse e recuperação (0005), concorrência de mutação (0006), resultados derivados
duráveis (0007) e as três operações assíncronas da App (0008). O fim da janela de manutenção global
ficou dentro de 0006, por ser consequência da exclusão por caminho e não decisão paralela; o
versionamento de payload ficou em 0004 e a identidade por parâmetros em 0007.

### Conclusão

**O núcleo arquitetural do A8 está concluído.** Não por ausência de itens numa lista, mas porque as
oito propriedades acima são afirmadas por teste e o desenho que elas descrevem é o que o código faz.

O que resta com o nome do A8 é empacotamento e ciclo de vida de processo — MSI, update e uninstall
com Worker vivo, supervisão no pacote instalado — e isso é backlog separado, não requisito
arquitetural pendente. O estado do Worker na bandeja **não** entra nessa lista: foi avaliado e
decidido como "não implementar" (ADR 0008).