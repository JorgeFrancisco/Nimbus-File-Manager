# Arquitetura App × Worker

Como o Nimbus File Manager executa trabalho pesado. Este documento descreve **o que existe hoje** e
basta por si: não é preciso conhecer o histórico do projeto para lê-lo.

- **Por que** cada decisão foi tomada está nos ADRs, linkados ao longo do texto.
- **Como se chegou até aqui** está em `../archive/`, preservado como histórico.

---

## 1. Dois processos

O produto roda em duas JVMs, cujos papéis são Spring profiles.

| | **App** (`app`) | **Worker** (`worker`) |
| --- | --- | --- |
| Serve UI e API | sim | não |
| Sobe o PostgreSQL embarcado e roda as migrations | sim | não |
| Observa a pasta da biblioteca | sim | não |
| Bandeja do sistema | sim | não |
| Valida pedidos e os enfileira | sim | não |
| Reivindica e executa trabalho | não | sim |
| Instala atualização da aplicação | sim | não |

A App **inicia e supervisiona** o processo do Worker: se ele cai, ela sobe outro, com backoff
crescente. O Worker herda o runtime Java e o jar da própria instalação, com heap configurado à parte
— é o ganho da separação, a interface não disputa memória com o trabalho.

**A supervisão desiste.** Depois de um número de falhas consecutivas de partida definido em
`WorkerRestartPolicy`, a App para de tentar e registra um ERROR dizendo que o trabalho de fundo não
vai rodar até alguém reiniciar. É deliberado — o que estiver errado a essa altura não se conserta com
mais uma JVM —, e é o único estado em que o produto fica sem Worker sem que nada na tela diga isso. O
sintoma que o usuário percebe é execução parada em `PENDING`.

Existe ainda o profile `app-worker-combined`, que ativa os dois papéis numa JVM só. É para
desenvolvimento: o Eclipse tem uma launch configuration versionada com ele.

**Cada processo escreve o seu log.** `logs/nimbus-file-manager-app.log` e
`…-worker.log` — separados porque um arquivo rotativo não é seguro entre duas JVMs. O console traz o
papel da linha (`[APP]`, `[WORKER]`, `[COMBINED]`), que é o que distingue os dois quando a execução é
combinada e há um console só.

> **ADR:** [0003 — App e Worker como processos separados](../adr/0003-app-e-worker-como-processos-separados.md) ·
> [0009 — Um arquivo de log por processo](../adr/0009-um-arquivo-de-log-por-processo.md)

**Consequência prática:** com o Worker indisponível, um pedido fica `PENDING` até que um Worker o
tome. A App **não** executa localmente como alternativa — não existe caminho de fallback.

---

## 2. `Execution`: a fila e o histórico

Um pedido é uma linha na tabela `execution`. O que aconteceu com ele são colunas da mesma linha. Não
existe segundo modelo persistente de job.

O que a linha carrega:

| Coluna | Para quê |
| --- | --- |
| `execution_type` | qual handler executa, e como a concorrência é contada |
| `request_payload` | o que o pedido tem de específico, com `schemaVersion` explícito |
| `source_path`, `target_path` | o recurso sobre o qual a execução se exclui (§4) |
| `dedup_key` | o que distingue dois pedidos de verdade |
| `status`, `claimed_by`, `claimed_at`, `lease_until`, `claim_count` | ciclo de vida (§3) |
| contadores, `phase`, `current_item_percent`, `status_message` | progresso e resultado |

**Deduplicação — a regra é 1 + 1, não "só pode existir uma".** Dois índices únicos parciais sobre
`(execution_type, dedup_key)`, um restrito a `PENDING` e outro a `RUNNING`, fazem o banco recusar o
duplicado; quem pede recebe de volta a execução que já está a caminho. São **dois** índices
justamente para permitir o caso legítimo: **uma esperando enquanto uma idêntica roda**. Um índice
sobre os dois estados proibiria isso.

O claim completa a regra do outro lado: **não toma a que espera enquanto a idêntica está em
execução**, porque tomá-la escreveria `RUNNING` uma segunda vez para a mesma chave — que é o que o
índice recusa.

Um `dedup_key` nulo opta por não ser deduplicado: é como uma conversão, um undo ou uma exclusão de
duplicados aceitam pedidos repetidos.

**`enqueueOrExisting` pergunta antes de inserir, e a recusa do banco continua sendo a autoridade.**
São dois casos diferentes:

- **o que não é corrida** — a linha já está na fila, commitada muito antes de alguém pedir de novo.
  É assim que todo boot se parece depois de a recuperação devolver o pedido anterior. Ela é
  encontrada e devolvida, e nenhum INSERT é tentado;
- **a corrida de verdade** — dois pedintes não encontram nada e ambos inserem. O índice recusa um
  deles, `enqueueOrExisting` devolve o que ficou, e se aquela linha terminar entre a recusa e a busca
  ele **pede uma vez mais** em vez de falhar por um motivo que deixou de existir.

A pergunta é só sobre o que está **esperando**: uma execução em andamento não proíbe sucessora, e
respondê-la com a que roda transformaria 1 + 1 em 1 + 0 sem nada falhar.

Na corrida, a camada de persistência registra a violação em nível de erro antes de qualquer código
classificá-la — **esse ERROR é ruído conhecido**, e o porquê de não ser eliminado está em
`ExecutionEnqueueService`. Uma violação de **outra** constraint não é tratada como duplicata: o nome
da constraint é conferido, e o que não for índice de deduplicação sobe.

**Payload versionado.** Um handler que encontra um `schemaVersion` que não é o seu **falha alto** em
vez de ler o payload pela metade.

**Mensagem sem idioma.** O que a linha guarda é um código e seus argumentos; quem lê resolve no
idioma de quem está olhando. Um enum é gravado como enum, nunca como a frase que ele vira — o Worker
não tem requisição atrás de si e, portanto, não tem idioma.

> **ADR:** [0004 — `Execution` como protocolo durável](../adr/0004-execution-como-protocolo-duravel.md)

### Como um pedido vira trabalho

```
    App                          PostgreSQL                     Worker
     │                                │                            │
     │  launcher valida + enfileira   │                            │
     ├───────────────────────────────►│  INSERT (PENDING)          │
     │                                │◄───────────────────────────┤  reserve()
     │                                │   UPDATE ... SKIP LOCKED   │  RUNNING + posse + lease
     │                                │                            │
     │                                │◄───────────────────────────┤  advisory locks dos caminhos
     │                                │                            │
     │  a tela lê a linha             │◄───────────────────────────┤  progresso, a cada lote
     │◄──────────────────────────────►│                            │
     │                                │◄───────────────────────────┤  status final + mensagem
```

Quem escreve o pedido são os *launchers*, por `ExecutionEnqueueService` — `enqueueOrExisting` quando
o tipo tem `dedup_key` e um segundo pedido idêntico deve receber o primeiro, `enqueue` quando não tem
e cada pedido é um trabalho próprio. Quem executa são os *handlers* (`ExecutionJobHandler`), um por
tipo. Um agendador ou o watcher de pasta também apenas enfileiram.

**Um pedido que não pode nomear o recurso de que precisa é recusado no launcher**, com a mensagem
localizada, em vez de virar uma linha que o Worker teria de reprovar: é o que acontece quando se pede
uma operação de quarentena sem pasta de quarentena configurada. O agendador equivalente simplesmente
não enfileira nada — ninguém está olhando um timer.

### Tipos e handlers

| `ExecutionType` | Handler |
| --- | --- |
| `INVENTORY` | `InventoryJobHandler` |
| `ORGANIZATION`, `ORGANIZATION_PREVIEW`, `UNDO` | `OrganizationJobHandler`, `OrganizationPreviewJobHandler`, `OrganizationUndoJobHandler` |
| `RECONCILE` | `ReconcileJobHandler` |
| `CONVERSION` | `ConversionJobHandler` |
| `DEDUP_DELETE` | `DuplicateDeletionJobHandler` |
| `EXPLORER_RENAME`, `EXPLORER_QUARANTINE`, `EXPLORER_DELETE` | `Explorer*JobHandler` |
| `QUARANTINE_RESTORE`, `QUARANTINE_PURGE`, `QUARANTINE_CLEANUP` | `Quarantine*JobHandler` |
| `CATALOG_PURGE` | `CatalogPurgeJobHandler` |
| `SIMILARITY_PHOTO`, `SIMILARITY_VIDEO` | `PhotoSimilarityJobHandler`, `VideoSimilarityJobHandler` |
| `FINGERPRINT_PHOTO`, `FINGERPRINT_VIDEO` | `PhotoFingerprintJobHandler`, `VideoFingerprintJobHandler` |
| `METADATA_REBUILD` | `MetadataRebuildJobHandler` |
| `LOCATION_REBUILD`, `GEO_DATASET_UPDATE` | `LocationRebuildJobHandler`, `GeoDatasetJobHandler` |
| `LIBRARY_SWITCH` | `LibrarySwitchJobHandler` |

---

## 3. Posse, vivacidade e recuperação

**`RUNNING` nasce só do claim.** O `UPDATE ... FOR UPDATE SKIP LOCKED` que muda o status escreve
`claimed_by`, `claimed_at` e `lease_until` na mesma instrução. Não há outro caminho para `RUNNING`.

**O lease é a autoridade de posse e de vivacidade, entre processos.** Uma execução está viva porque
seu lease está no futuro; está abandonada porque o dono parou de renovar. Nenhum processo precisa
conhecer nada que outro guarde em memória — e nada em memória decide ciclo de vida.

**Renovação por heartbeat, só pelo dono.** `lease_until` significa posse, nunca prazo de
processamento: um job renovado corretamente dura horas. O `UPDATE` de renovação é condicionado ao
dono, então um segundo Worker não estende um lease que não tem.

**Uma política de recuperação**, executada por qualquer processo ao subir (`ExecutionReclaim`). Para
cada lease vencido:

| Situação | O que acontece |
| --- | --- |
| Handler declara `resumable()` | volta para a fila |
| Não retomável | fechada como interrompida, e um `RECONCILE` é enfileirado para as pastas que ela tocava |
| Tentativas esgotadas | encerrada em erro, definitivamente |

**A ordem dos passos é a arquitetura, não um detalhe:**

```
reivindicar  →  adquirir os locks de caminho  →  contar a tentativa  →  executar
```

Reivindicar é uma transação curta que termina antes de qualquer trabalho começar, então nenhum lock
de linha fica seguro enquanto arquivos se movem. Os locks de caminho vêm depois, em outra conexão. A
tentativa é contada só com os dois na mão, e nada do domínio roda antes de essa contagem estar
persistida.

`claim_count` é o freio de poison job, e **nunca decrementa**. O que o consome:

| Situação | Tentativa | O que acontece |
| --- | --- | --- |
| Caminho ocupado por outra execução | **não** consome | *hand-back*: volta para a fila com um backoff e roda quando o outro soltar |
| Qualquer outra falha entre reivindicar e contar | consome | é cobrada e tratada como falha comum — repetida enquanto houver orçamento, encerrada quando não houver |
| Falha durante o trabalho | consome | mesma política |

A segunda linha existe porque a alternativa não tem freio: uma linha que voltasse para a fila com o
contador congelado em zero seria reivindicada de novo, falharia de novo, e o freio nunca engataria
porque lê justamente o contador que não se moveu.

Um `PENDING` não é trabalho abandonado — nunca foi reivindicado, não tem lease para vencer.

> **ADR:** [0005 — Claim, lease, vivacidade e recuperação](../adr/0005-claim-lease-e-recuperacao.md)

---

## 4. Concorrência sobre arquivos

**Fronteira de mutação.** Mudar arquivos do usuário ou o catálogo da coleção passa por ports
(`LibraryFileMutations`, `CatalogMutations`). Quem os detém é trabalho do Worker alcançável a partir
de um handler — nada mais, sem exceções.

**Exclusão por caminho.** Uma execução cujo trabalho é uma pasta declara onde trabalha, e o
dispatcher toma advisory locks do PostgreSQL sobre a cadeia de prefixos canônicos desses caminhos.
Ancestrais são tomados em modo compartilhado:

- uma operação exclusiva sobre uma pasta impede operações nela e abaixo dela;
- árvores irmãs independentes **não** se bloqueiam por compartilharem um ancestral.

**Nem toda execução tem caminho.** Trabalho definido por uma *consulta*, e não por uma pasta, declara
isso em `ExecutionJobHandler.requiresPathLock()`. Hoje são cinco tipos, e cada um pelo mesmo motivo —
o que eles percorrem é o resultado de uma pergunta ao banco, não uma árvore:

| Tipo | O que ele percorre |
| --- | --- |
| `FINGERPRINT_PHOTO`, `FINGERPRINT_VIDEO` | os arquivos daquele tipo que ainda não têm impressão digital |
| `SIMILARITY_PHOTO`, `SIMILARITY_VIDEO` | as impressões digitais que já existem |
| `CATALOG_PURGE` | as linhas de catálogo cujo arquivo sumiu há mais tempo que a janela |

Obrigá-los a inventar um caminho os faria esperar por — e bloquear — trabalho com o qual não têm nada
a ver. Três propriedades sustentam isso:

- **o padrão é sim**, então quem esquece de responder fica dentro da exclusão;
- **quem alcança `LibraryFileMutations` não pode responder não**, e isso é verificado, não confiado;
- **a decisão vem do tipo, nunca de haver ou não um caminho preenchido na linha.** "Sem caminho, logo
  sem lock" deixaria uma execução mutadora sair da exclusão por um campo esquecido; um tipo que exige
  caminho e não nomeia nenhum **falha alto** no dispatcher.

Essa última é a barreira final, não a primeira: o launcher recusa antes de enfileirar um pedido que
não consegue nomear seu recurso (§2). O dispatcher existe para o caso de um launcher futuro errar.

**Quem chega segundo espera, não falha:** a execução volta para a fila com o orçamento de tentativas
intacto e roda quando a primeira soltar. Só contenção de caminho é isenta de gastar tentativa;
qualquer outra falha entre o claim e a contagem consome uma, para que nada possa ser retomado sem
limite (§3).

**Não existe janela de manutenção global.** Dois workloads passam a se excluir simplesmente
declarando o mesmo caminho — é assim que o rebuild de localização e a atualização da base geográfica
se excluem, sem que nenhum saiba que o outro existe.

Todo movimento de arquivo do usuário passa por `SecureFileMove`: baseline SHA-256, verificação byte a
byte e rollback.

> **ADR:** [0006 — Concorrência de mutação](../adr/0006-concorrencia-de-mutacao-do-filesystem.md)

---

## 5. Resultados que a tela lê

Contadores e uma frase cabem na linha da execução. O que é maior — os grupos de fotos semelhantes, o
plano de uma organização, a simulação de um rebuild de metadados — tem tabela própria, com o id da
execução como chave primária.

**Nada parcial aparece.** Ou o registro nasce `BUILDING` e é promovido por um `UPDATE` condicional,
ou o próprio status da execução terminada é a bandeira que o torna visível.

**Os parâmetros fazem parte da identidade do resultado**: um agrupamento pedido a 85% não é
respondido com um calculado a 90%. E um resultado **desatualizado é entregue com a ressalva**, não
escondido — a biblioteca mudou desde o cálculo, mas a resposta continua verdadeira sobre o que
examinou.

Quando não há resultado publicado para o que se pediu, a API responde `202` com a execução a
acompanhar.

### Cancelamento

O pedido de parada é durável (`cancel_requested` na linha), porque quem clica e quem trabalha são
processos diferentes. O handler o consulta em **pontos naturais do próprio trabalho**, nunca numa
cadência inventada, e o ponto tem de ser um em que **nada durável está pela metade**. Quanto disso
existe depende do protocolo de publicação de cada tipo, então o suporte **não é uniforme** — e onde
não existe, isso é dito em vez de contornado.

| Tipo | Onde pode parar |
| --- | --- |
| `SIMILARITY_PHOTO`, `SIMILARITY_VIDEO` | cooperativo: na entrada, uma vez por candidato durante a clusterização, e imediatamente antes de escrever o agrupamento |
| `GEO_DATASET_UPDATE` | apenas antes de a aquisição começar |
| Demais tipos que percorrem itens | nos pontos que cada um já usa para reportar progresso |

**Semelhança** para em qualquer momento até a publicação porque tudo antes dela é cálculo: o
agrupamento só é escrito depois de a análise retornar, e a resposta vigente só é aposentada pela
publicação. Cancelar não deixa nem uma linha `BUILDING` para trás, e o agrupamento que a tela já
mostrava continua sendo a resposta.

**A base geográfica** não é cancelável durante a aquisição, e isso é deliberado. O protocolo é baixar
para um staging, importar numa transação própria e só então promover o que foi baixado; entre o
import e a promoção as tabelas já descrevem uma versão e o disco ainda descreve outra. Não há ponto
seguro ali, e inventar um trocaria um cancelamento por uma base inconsistente. Morte do processo no
meio continua coberta pelo mesmo protocolo, que é por que o tipo é retomável (§3).

**Cancelar termina em `CANCELLED`, nunca em `ERROR`.** Quem apertou um botão não causou uma falha, e
o histórico precisa distinguir as duas coisas — inclusive de `INTERRUPTED`, que é interrupção
administrativa.

> **ADR:** [0007 — Resultados derivados duráveis](../adr/0007-resultados-derivados-duraveis.md)

---

## 6. O que roda de forma assíncrona na App

Exatamente três operações, e nenhuma delas é processamento da biblioteca:

| Runner | Por que não é do Worker |
| --- | --- |
| `UpdateInstallAsyncRunner` | encerra a própria aplicação para o instalador rodar |
| `ExternalToolInstallAsyncRunner` | instala o ffmpeg que o Worker usa — dependência circular se fosse dele |
| `CatalogBackupAsyncRunner` | `pg_dump`/`pg_restore` no cluster que a App supervisiona; o restore derruba as conexões do Worker |

Um quarto `@Async` na App exige decisão arquitetural nova.

> **ADR:** [0008 — As três operações assíncronas da App](../adr/0008-operacoes-assincronas-da-app.md)

---

## 7. O que o build protege

Estas propriedades não são convenção: são teste, e quebram o build.

| Teste | O que ele impede |
| --- | --- |
| `MutationBoundaryArchitectureTest` | um controller alcançar um port de mutação; uma classe nova deter a capacidade sem ser trabalho do Worker; qualquer exceção temporária à regra; um handler que alcança `LibraryFileMutations` declarar-se sem lock de caminho |
| `HeavyWorkloadArchitectureTest` | um `@Async` fora dos três; uma tela alcançar um serviço pesado; um agendador executar em vez de enfileirar |
| `ExecutionLivenessIntegrationTest` | vivacidade deixar de ser respondida pelo lease entre conexões distintas |
| `ExecutionQueueIntegrationTest` | o claim concorrente, a deduplicação e os `UPDATE` condicionais regredirem |
| `ExecutionQueueDedupIntegrationTest` | o claim voltar a tomar um pedido que espera enquanto o idêntico roda — o que o índice recusa e o loop repetiria |
| `ExecutionEnqueueRaceIntegrationTest` | dois pedidos idênticos simultâneos, em transações reais, deixarem de virar uma execução só que ambos recebem |
| `FingerprintBacklogEndToEndIntegrationTest` | um tipo voltar a ter teste, histórico e build verde sem nunca ter atravessado o dispatcher |
| `SimilarityCancellationIntegrationTest` | um cancelamento publicar resultado parcial, aposentar a resposta vigente ou terminar como erro |
| `OperationLockServiceIntegrationTest`, `OwnershipLossIntegrationTest` | os advisory locks e a reconfirmação de posse regredirem |

---

## 8. Onde mexer, ao adicionar um workload

1. Um `ExecutionType` novo.
2. Um payload `record` com `schemaVersion`, em `application/dto`.
3. Um *launcher* na App: valida, escolhe a `dedup_key`, declara os caminhos e enfileira — ou recusa
   com a mensagem pronta, se o pedido não puder nomear o recurso de que precisa.
4. Um `ExecutionJobHandler`: decodifica o payload (recusando esquema desconhecido), reporta progresso
   na linha, honra o sinal de parada onde houver ponto seguro para isso (§5) e fecha com status e
   mensagem. Se o trabalho é uma consulta e não uma pasta, responde `requiresPathLock()` — e não pode
   respondê-lo se alcança `LibraryFileMutations`.
5. Um *reader* para a tela, que lê da linha — nunca de um campo em memória.
6. Se o resultado não couber em contadores, uma tabela pendurada na execução e uma migration.
7. Chaves de mensagem em **todos** os bundles.

Por padrão o workload é do Worker. Ficar na App exige decisão registrada em ADR.