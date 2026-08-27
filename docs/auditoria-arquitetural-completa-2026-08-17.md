# Auditoria arquitetural completa — Nimbus File Manager

**Data da auditoria:** 17 de agosto de 2026  
**Natureza:** auditoria independente e somente leitura  
**Escopo:** documentação, código, migrations, testes, PostgreSQL real, filesystem e planos de execução  
**Estado nesta etapa:** nenhuma implementação recomendada neste documento foi realizada

## A. Resumo executivo

O Nimbus é hoje um catálogo local inteligente de arquivos, com PostgreSQL como núcleo durável e o
filesystem como fonte física. Ele inventaria, monitora, extrai metadata, acompanha mudanças, organiza,
coloca em quarentena, detecta duplicatas/similaridade e registra execuções em background. Essa descrição
está consistente entre o [README](../README.md), a maior parte do código e o banco real.

O produto tem uma arquitetura acima da média em quatro áreas:

- separação App × Worker;
- fila durável com claim, lease, fencing e deduplicação 1+1;
- funções transacionais para mudanças de localização;
- publicação durável de resultados derivados.

Mas a auditoria encontrou uma quebra central:

> Existem atualmente duas authorities de conteúdo.

`ContentReconciliation` declara ser a única porta de mudança de conteúdo e garante revisão, evento e
invalidação transacional de derivados. Porém o Inventory atualiza diretamente `sha256`, tamanho, metadata
e lifecycle por `CatalogFileMapper`, sem avançar `content_revision`, escrever `CONTENT_CHANGED` ou eliminar
fingerprints antigos.

Consequências possíveis e concretamente suportadas pelo código:

- fingerprint antigo pode continuar associado a bytes novos;
- exclusion ligada à revisão anterior continua valendo para o conteúdo novo;
- relações de similaridade podem permanecer parcialmente vigentes;
- `content_revision` deixa de representar a geração real;
- mudanças vistas pelo Inventory não deixam evento `CONTENT_CHANGED`.

Classificação: **P0 — risco de corrupção lógica do catálogo**, embora não tenha sido provado que isso já
ocorreu nos dados atuais.

Outros resultados principais:

- `catalog_file_event` não é um journal completo. Não registra descoberta inicial e é apagado em cascata
  quando `catalog_file` é purgado.
- A promoção `MISSING → ACTIVE` pelo caminho unitário grava `REAPPEARED` fora da transação que promoveu o
  registro.
- Quarentena e restauração alteram lifecycle diretamente, mas não escrevem os eventos lifecycle
  equivalentes usados por outras capacidades.
- Imutabilidade de eventos e grande parte das invariantes de execução são disciplina da aplicação, não
  proteção do PostgreSQL.
- A fila/Worker é arquiteturalmente forte e muito bem testada.
- O banco real está consistente com o filesystem atual: 146.217 `ACTIVE` existentes e exatamente um
  `MISSING` inexistente.
- Não há P0 de perda de arquivos reais confirmado.
- A fotografia operacional mostrou um Worker parado e uma execução com lease expirado. O modelo a
  identifica corretamente como abandonada, mas a recuperação depende do App/Worker voltar a executar.
- O agrupamento FFmpeg de fingerprints está falhando repetidamente e caindo para process-per-item, um
  hotspot medido.

Nenhum arquivo, schema, dado, documento ou estado Git foi modificado durante a auditoria que originou este
relatório.

## B. Mapa das principais capabilities

| Capability | Responsabilidade atual | Processo executor | Persistência principal |
| --- | --- | --- | --- |
| Inventory | Descobrir arquivos, extrair metadata e criar/atualizar catálogo | Worker | `catalog_file`, location, metadata/photo/video |
| Watcher/RDCW | Produzir sinais de mudanças ao vivo | App | estado transitório; USN cursor durável |
| USN catch-up | Recuperar mudanças enquanto o App estava parado | App | `usn_journal_cursor` |
| Reconcile | Comparar catálogo conhecido com o disco e reparar drift | Worker | lifecycle/location/events/execution |
| Organization | Planejar e mover arquivos com undo | Worker | plan, movement, events, execution |
| Explorer mutation | Rename/quarantine/delete | Worker | movement, location, lifecycle |
| Quarantine | Soft-delete, restore, purge | Worker | lifecycle, movement, catálogo |
| Conversion | Converter mídia e catalogar saída | Worker | conversion results, catalog, execution |
| Metadata rebuild | Reextrair metadata/preview | Worker | metadata/photo/video/preview |
| Fingerprint | Calcular pHash de fotos/vídeos | Worker | fingerprint/failure/task |
| Similarity | Calcular e publicar agrupamentos | Worker | relations, coverage, grouping |
| Geolocation | Instalar dataset e resolver coordenadas | Worker | boundaries/state/cache/location |
| Execution platform | Fila, posse, progresso e histórico | App enfileira; Worker executa | execution/steps/errors/metrics |
| Backup/update/tool install | Operações estruturais do supervisor | App | estado próprio/logs |

O README confirma que trabalho pesado e mutações da biblioteca pertencem ao Worker. As únicas exceções
`@Async` da App são backup, instalação de ferramenta e atualização, conforme o
[ADR 0008](adr/0008-operacoes-assincronas-da-app.md). O código contém exatamente essas três classes.

## C. Mapa de entidades e tabelas

### Núcleo do catálogo

| Estrutura | Responsabilidade e identidade | Lifecycle/escritores | Authority/derivação |
| --- | --- | --- | --- |
| `catalog_file` | Identidade lógica estável; PK `id`, UUID público; conteúdo, revisão e lifecycle | Inventory, content reconciliation, quarantine/restore/undo | Authority atual de identidade, conteúdo declarado e lifecycle |
| `catalog_file_location` | Uma localização atual/última conhecida por arquivo; PK/FK = file ID | Inventory inicial e `CatalogLocationWriter` | `current_path` autoritativo; `path_key` e `current_folder` gerados |
| `catalog_file_event` | Fatos de path, lifecycle e conteúdo | SQL functions/writers | Histórico parcial; intenção append-only, não protegida |
| `media_metadata` | Metadata genérica de mídia; PK/FK = file ID | Inventory/metadata rebuild | Reconstruível |
| `photo` / `video` | Detalhes específicos | Inventory/metadata rebuild | Reconstruíveis |
| `movement` | Operação física preparada antes do toque no arquivo | Organization/quarantine/explorer | Histórico operacional; atualmente apagável em cascata |
| `self_written_path` | Supressão temporária de eventos próprios | Mutadores da biblioteca | Cache operacional efêmero |

A separação identidade/localização/evento nasceu na
[V30](../src/main/resources/db/migration/V30__catalog_identity_separates_from_location.sql). A migration
define:

- `catalog_file`: identidade, conteúdo e lifecycle;
- `catalog_file_location`: localização atual;
- `catalog_file_event`: fatos imutáveis.

O modelo JPA sustenta isso em `CatalogFile`, `CatalogFileLocation` e `CatalogFileEvent`.

### Duplicatas e dados derivados

| Estrutura | Responsabilidade | Identity/invariantes | Classificação |
| --- | --- | --- | --- |
| `media_fingerprint` | pHash/amostras por arquivo e algoritmo | unique file/kind/algorithm/sample; payload XOR | Caro, regenerável |
| `fingerprint_failure` | Tentativas esgotadas/falhas | unique por file/kind/algorithm | Cache operacional |
| `fingerprint_rebuild_task` | Coordenação de rebuild | Por arquivo/tarefa | Efêmero |
| `duplicate_exclusion_file` | Decisão do usuário para file+revision | Uma por arquivo, guarda revision | Decisão insubstituível |
| `duplicate_folder_exclusion` | Exclusão de pasta | Path canônico | Decisão insubstituível |
| `similarity_relation` | Pares calculados | Par canônico + família algorítmica | Caro, regenerável |
| `similarity_relation_coverage` | Universo já comparado | Família + arquivo | Regenerável |
| `similarity_grouping/group/member` | Resultado publicado | Ligado à execução/família | Caro, regenerável |

Ponto importante: `media_fingerprint` **não possui `content_revision`**. A vigência depende de:

1. apagar fingerprints quando a revisão avança;
2. impedir uma escrita tardia usando `FingerprintWriter.insertForRevision`.

Essa construção é transacionalmente defensável, mas não é autodescritiva: olhando apenas a linha do
fingerprint, não se consegue provar de qual revisão ela veio.

### Geolocalização

| Estrutura | Responsabilidade | Classificação |
| --- | --- | --- |
| `geo_dataset_state` | Identidade/estado do dataset instalado | Authority do dataset lógico |
| `geo_admin_boundary` | Polígonos e bbox | Regenerável da fonte |
| `geo_resolution_cache` | Cache de resolução | Descartável |
| `media_geo_location` | Resultado de geocoding por mídia | Regenerável |
| `usn_journal_cursor` | Checkpoint do journal NTFS | Estado operacional durável |

### Execuções

| Estrutura | Responsabilidade | Observação |
| --- | --- | --- |
| `execution` | Intenção, fila, status, posse, progresso e histórico | Aggregate central |
| `execution_phase` | Histórico de fases | Histórico técnico |
| `execution_step` | Etapas/mensagens | Histórico técnico |
| `execution_error` | Erros por item | Histórico técnico |
| `execution_metrics` | Métricas agregadas | Observabilidade |
| `execution_metrics_category` | Espera/execução por categoria | Observabilidade |
| `worker_instance` | Último heartbeat por instância | Registro de liveness, não “Worker atual” |

`execution` tem muitas responsabilidades, mas elas pertencem ao mesmo protocolo durável. Não considero
necessária uma divisão imediata da fila e do histórico.

### Operações e estado do usuário

- `organization_plan_record/item`: resultado temporário de preview; regenerável.
- `conversion_item_result`: histórico por item da conversão.
- `metadata_rebuild_preview/item`: resultado temporário.
- `app_setting`: configuração global.
- `app_user`: usuário e credenciais — insubstituível.
- `user_access_log`: auditoria de acesso — histórico a preservar.
- `user_page_preference`: preferência do usuário.
- Flyway: `flyway_schema_history`, 58 migrations aplicadas até V58, todas `success=true`.

## D. Mapa das authorities

| Fato | Authority correta hoje | Findings |
| --- | --- | --- |
| Arquivo existe fisicamente agora | Filesystem, no instante da observação | `lifecycle_status` é projeção durável, não prova física instantânea |
| Identidade lógica | `catalog_file_public_id` | Correto; path e filesystem ID são evidências, não identidade |
| Path atual/último conhecido | `catalog_file_location.current_path` | Correto |
| Forma canônica do path | Função PostgreSQL + coluna gerada `path_key` | Correto; evita regra duplicada em Java |
| Ocupação ativa de um path | Lifecycle + location, arbitrados pelas portas SQL | Não há constraint cross-table |
| Conteúdo atual declarado | `catalog_file.sha256/size/modified_at/content_revision` | **Quebrado: Inventory também escreve diretamente** |
| Histórico de localização/conteúdo | `catalog_file_event` | Parcial, apagável e não imutável no DB |
| Fingerprint calculado | `media_fingerprint` | Correto por família algorítmica |
| Fingerprint vigente | Existência da linha + disciplina de invalidação | Revisão não está na linha; depende de porta única que hoje não é única |
| Metadata vigente | `media_metadata`/photo/video | Reconstruível; Inventory e rebuild escrevem |
| Exclusion de arquivo | `duplicate_exclusion_file` + revision | Decisão de usuário; fica errada se revision não avançar |
| Similarity publicada | grouping publicado ligado à execução | Bom modelo |
| Dataset GEO instalado | `geo_dataset_state`, consistente com boundaries | Arquivos baixados são staging/cache |
| Execução ativa | `execution`, mas `RUNNING` só é vivo com lease válido | Correto |
| Posse | `claimed_by + claim_count + lease_until` | Correto e fenced |
| Progresso/ETA | Colunas da execução interpretadas por um modelo por tipo | Correto; [ADR 0010](adr/0010-uma-authority-de-progresso-e-estimativa.md) |
| Worker vivo | Heartbeat recente, não mera existência em `worker_instance` | Correto |
| Pending watcher | Memória da App | Transitório; recuperado por USN/reconcile |
| Resultados derivados | Tabelas publicadas ligadas à execução | Bom; [ADR 0007](adr/0007-resultados-derivados-duraveis.md) |

## E. Fluxo CatalogFile / Location / CatalogFileEvent

| Caso | Estado esperado | Evento esperado | Implementação/teste | Divergência |
| --- | --- | --- | --- | --- |
| Descoberta inicial | Novo `ACTIVE` + location | `CATALOGUED`/`DISCOVERED`, se o journal for completo | Inventory salva file+location atomicamente; `InitialPlacementIntegrationTest` | Nenhum evento |
| Reaparecimento em lote | `MISSING → ACTIVE` | `REAPPEARED` | Mesmo fluxo transacional; `DiscoveryAtAKnownPathIntegrationTest` | Correto |
| Reaparecimento unitário | `MISSING → ACTIVE` | `REAPPEARED` atômico | Promotion em transação; evento depois | **Não atômico** |
| Rename/move reconhecido | Atualiza path | `RENAMED`/`MOVED` | `CatalogLocationWriter` + função SQL | Bom |
| Folder relocation | Atualiza todos os paths | Um fato por arquivo | Função SQL/idempotência | Bom hoje; V48 corrigiu replay quebrado |
| Organização | Path novo + movement moved | Evento de location | Mesma transação em `OrganizationMovePersistence` | Bom |
| Quarentena | Path de quarentena + `DELETED` | Location + lifecycle/quarantine | Location event + movement; lifecycle direto | Sem evento lifecycle `DELETED` |
| Restore | Path restaurado + `ACTIVE` | Location + `RESTORED`/`REAPPEARED` | Location event + movement; lifecycle direto | Sem evento lifecycle explícito |
| Delete externo | `ACTIVE → MISSING` | `MISSING` | Função `mark_catalog_files_missing`; reconcile | Bom |
| Delete solicitado fora da quarentena | `ACTIVE/MISSING → DELETED` | `DELETED` | `CatalogLifecycleWriter.markDeleted` / V47 | Bom |
| Purge | Remove catálogo e derivados | Tombstone/audit deveria sobreviver | Delete de `catalog_file` | Eventos e movements eliminados por cascade |
| Mudança de conteúdo via watcher | Revision++, digest novo, derivados removidos | `CONTENT_CHANGED` | `ContentReconciliation`, rollback e CAS testados | Bom |
| Mudança de conteúdo via Inventory | Mesmo esperado | Mesmo esperado | Mapper sobrescreve campos diretamente | **P0** |
| Reconcile encontra newcomer | Sem criação direta | Nenhum ainda | Enfileira Inventory | Coerente |

A V30 chamou `catalog_file_event` de fatos imutáveis, mas declarou explicitamente que essa imutabilidade
ainda não era imposta pelo schema. Também não existe história anterior ao novo modelo: a própria V30 diz
que não carregou os dados antigos.

No banco real existem:

- 146.218 arquivos;
- 146.218 locations;
- somente **um** evento, do tipo `MISSING`;
- zero eventos de descoberta, movimento, reaparecimento ou conteúdo.

Portanto, tratar essa tabela como “histórico completo da biblioteca” seria incorreto.

## F. Fluxo Watcher / USN / Inventory / Reconcile

```text
RDCW/WatchService ──► sinal de mudança ──► reconhecimento rápido
       │                                      │
       │                                      ├─ rename/move provado → location door
       │                                      ├─ modify suspeito → CONTENT_VERIFICATION
       │                                      └─ inconclusivo → pending debounce
       │
USN catch-up ─────► mudanças durante downtime / recovery reason
                                              │
                                              ▼
                                  RECONCILE → INVENTORY se necessário
```

Responsabilidades:

- **Watcher/RDCW:** baixa latência; não é fonte definitiva.
- **USN:** cobre o intervalo offline no Windows/NTFS quando o journal é legível.
- **Inventory:** descobre newcomers e extrai conteúdo/metadata.
- **Reconcile:** detecta arquivos catalogados que sumiram, repara paths e pede Inventory para o que
  chegou.

Detalhes:

- `PhysicalTreeWatcher`/WatchService não identifica rename; observa delete/create.
- RDCW/USN podem entregar continuidade por pares de eventos ou filesystem identity.
- Rename somente é aplicado diretamente quando identidade/path anterior produzem uma conclusão não
  ambígua.
- Delete ao vivo não conclui lifecycle: deixa o reconcile confirmar ausência.
- Overflow ou journal gap produzem recovery conservador.
- Em fallback portátil, eventos perdidos dependem do reconcile periódico e do Inventory subsequente.
- O pending/debounce da App é memória transitória. No Windows, o USN e o reconcile fornecem recuperação;
  no fallback WatchService, somente o reconcile periódico sobrevive a crash.
- Um arquivo novo entra exclusivamente pelo Inventory, coerente com o README.

Veredito: o desenho é coerente. A falha não está na divisão de responsabilidade, mas na escrita de
conteúdo feita pelo Inventory depois de descobrir/reanalisar o arquivo.

**NÃO PROVADO:** funcionamento end-to-end do RDCW/USN real durante reboot, wrap do journal e corrida com
um filesystem real. Existem testes detalhados sobre seams/fakes, mas não uma prova automatizada completa
usando o journal NTFS do host.

## G. Fluxo Execution / Queue / Worker

1. **Enqueue**

   `ExecutionEnqueueService` normaliza o pedido, gera `dedup_key`, salva `PENDING` e notifica o Worker.

2. **Deduplicação 1+1**

   O PostgreSQL possui dois índices parciais:

   - `ux_execution_pending_dedup`: no máximo um `PENDING` por type/key;
   - `ux_execution_running_dedup`: no máximo um `RUNNING` por type/key.

   Isso permite um running e um sucessor waiting.

3. **Reserve/claim**

   `ExecutionQueue.reserve` usa `FOR UPDATE SKIP LOCKED`, prioridade e disponibilidade. Claim grava
   `RUNNING`, owner e lease na mesma instrução.

4. **Attempt**

   `claim_count` só aumenta depois dos locks necessários. Contenção de path não consome tentativa.

5. **Lease/fencing**

   Renovação exige:

   - mesmo `workerId`;
   - mesmo `claim_count`;
   - status `RUNNING`;
   - lease ainda não expirado.

6. **Execução**

   O handler é selecionado por `ExecutionType`. Todos os 24 tipos atuais têm handler.

7. **Cancelamento**

   Running recebe `cancel_requested`; pending pode terminar imediatamente como cancelled.

8. **Failure/retry**

   Falhas transitórias requeue com backoff, respeitando orçamento de tentativas. Poison jobs terminam em
   vez de ficarem invisíveis.

9. **Reclaim**

   Lease expirado:

   - workload resumível volta para `PENDING`;
   - não resumível é encerrado e pode gerar reconcile;
   - successor waiting pode superseder o abandonado.

10. **Finish**

    Escritas tardias precisam do fence da tentativa. Uma tentativa anterior não pode terminar ou atualizar
    a posterior.

Essa é a parte mais madura do sistema. Os testes cobrem dois claimers concorrentes, lease expirado,
renovação concorrente, escrita tardia, reclaim duplicado, cancellation race e dedup 1+1.

Possível risco não observado: prioridade é ordenada por valor e ID, sem aging explícito. Uma chegada
constante de prioridade maior pode, conceitualmente, causar starvation. **HIPÓTESE**, não comportamento
medido.

Não encontrei duas implementações App/Worker para a mesma capability. O perfil combinado executa ambos
os papéis na mesma JVM para desenvolvimento, mas usa o mesmo protocolo e handlers.

## H. Dados insubstituíveis × regeneráveis

### A. Insubstituíveis

- arquivos reais do usuário;
- usuários, credenciais e 2FA;
- settings escolhidos pelo usuário;
- preferências pessoais;
- exclusions de arquivos e pastas;
- decisões de quarentena ainda reversíveis;
- histórico de movimentos necessário para undo;
- access audit;
- decisões explícitas de organização/conversão que precisem de auditoria.

### B. Reconstruíveis a partir dos arquivos

- `catalog_file`, se identidade histórica puder ser abandonada;
- location;
- metadata/photo/video;
- hashes;
- geolocation da mídia;
- thumbnails;
- filesystem identity observada.

### C. Caches

- `geo_resolution_cache`;
- `self_written_path`;
- geometries em memória;
- filas derivadas de fingerprint;
- failure/task de rebuild, se uma reconstrução total for escolhida.

### D. Caros, mas regeneráveis

- fingerprints;
- similarity relations/coverage/groupings;
- geo boundaries;
- previews de metadata;
- organization previews;
- métricas de processamento não históricas.

### E. Histórico a preservar

- executions/steps/errors;
- movements;
- eventos de catálogo, se o produto mantiver a promessa de auditoria;
- access log;
- resultados finais de conversões;
- snapshots/tombstones necessários para explicar purge e decisões anteriores.

O schema atual contradiz a categoria E: as FKs de `catalog_file_event` e `movement` usam
`ON DELETE CASCADE`, de modo que um purge do catálogo apaga o histórico associado.

## I. Divergências documentação × implementação

1. **“Full audit history” versus histórico parcial e apagável**

   O README promete “full audit history and undo support”. Porém:

   - descoberta inicial não gera evento;
   - V30 descartou história anterior;
   - purge remove eventos e movements em cascata;
   - quarantine/restore não escrevem eventos lifecycle explícitos.

2. **“Única porta de conteúdo” versus Inventory**

   `ContentReconciliation` afirma que watcher, walk e secure move convergem na mesma decisão. O Inventory
   não a chama.

3. **V49 diz que reativação e evento commitam juntos**

   A migration afirma explicitamente isso. O caminho em lote obedece; o caminho unitário não.

4. **Documentação App × Worker**

   Está bem aderente à implementação. Claim/lease, ausência de fallback no App e as três exceções
   `@Async` são verificáveis em
   [worker-architecture.md](architecture/worker-architecture.md).

5. **Documento de evolução**

   Corretamente se apresenta como direção/sugestão, não authority normativa. Sua premissa de uma
   instalação/uma biblioteca corresponde ao `WATCH_FOLDER` global.

## J. Divergências implementação × testes

### Lacunas confirmadas

- `DiscoveryAtAKnownPathIntegrationTest` testa reaparecimento com **os mesmos bytes**, não com bytes
  diferentes.
- Não há teste de Inventory provando que uma mudança real:

  - incrementa revision;
  - escreve `CONTENT_CHANGED`;
  - elimina fingerprint;
  - invalida exclusion da geração anterior.

- O teste de reaparecimento atravessa o caminho batch; não prova a atomicidade do caminho unitário usado
  por `ConversionCatalogService`.
- Não existe teste que demonstre história completa desde a descoberta até o purge.
- Não existe proteção/teste de imutabilidade no PostgreSQL usando permissões.
- Os testes USN/RDCW exercitam fakes/seams; não provam toda a integração NTFS real.
- A V48 documenta que o replay idempotente de folder relocation ficou quebrado porque só o primeiro
  caminho era exercitado. Hoje há testes de replay em `CatalogLocationWriterIntegrationTest` e
  `ExplorerCrashRecoveryIntegrationTest`, portanto a lacuna específica foi corrigida.

### Qualidade geral

A suite não é meramente mock-based. Há muitos testes PostgreSQL/Testcontainers discriminantes,
especialmente para:

- conteúdo;
- rollback;
- revision guards;
- queue;
- lease/reclaim;
- localização;
- publicação de derivados.

Foram encontrados relatórios preexistentes de 140 classes/1.110 testes, com zero failures/errors/skips,
produzidos em 17/08/2026. Eles são uma execução parcial recente, não prova da suite inteira.

**Suite completa verde neste checkpoint: NÃO PROVADO.** Maven não foi executado durante a auditoria
original para mantê-la estritamente observacional.

## K. Findings confirmados

| Prioridade | Finding | Esforço | Risco da mudança | Breaking/rebuild |
| --- | --- | ---: | ---: | --- |
| **P0** | Inventory sobrescreve conteúdo sem revision/event/invalidação de derivados | Médio | Médio | Recomenda-se rebuild de fingerprints/relations |
| **P1** | Journal não registra discovery e é apagado por purge | Alto | Alto | Sim; baseline/reinventory |
| **P1** | Reaparecimento unitário promove antes de gravar evento em outra transação | Baixo | Baixo | Não exige rebuild |
| **P1** | Quarantine/restore usam lifecycle direto, diferente das portas lifecycle | Médio | Médio | Migração de semântica/história |
| **P2** | Imutabilidade de evento e invariantes de execução não são protegidas no DB | Médio | Médio | Migrations/roles |
| **P2** | Fingerprint vigente não carrega content revision | Médio/alto | Médio | Rebuild recomendado |
| **P2** | Hard purge destrói movement/audit histórico | Médio/alto | Alto | Tombstones/archive |
| **P3** | Ausência do Worker só fica evidente por heartbeat/health/log/PENDING | Médio | Baixo | Não |
| **P3** | Grouped FFmpeg falha repetidamente e cai para process-per-item | Médio | Médio | Fingerprints continuam válidos |
| **P3** | Query de pending fingerprint degrada conforme o prefixo já processado cresce | Médio | Baixo | Índice/query |
| **P3** | 32.535 mídias com GPS estão sem geolocation apesar do dataset instalado | Operacional | Baixo | Rebuild GEO |
| **P4** | `execution` tem semântica de contadores historicamente sobrecarregada | Já mitigado | Baixo | Não |

### Performance

**MEDIDO**

- lookup de 1.000 paths: ~20 ms;
- fetch de 100 fotos pendentes: ~40 ms;
- contagem de 83.585 fotos pendentes: ~95 ms;
- primeira página de reconcile com 500 registros: ~47 ms;
- bbox GEO country em cache frio: ~22,7 ms;
- município brasileiro após aquecimento: ~0,8 ms;
- janela de fingerprint real: aproximadamente 1.450 itens/307 s, ~4,72 itens/s;
- logs mostram repetidos fallbacks de grupos de 25 fotos para leitura individual.

**INFERIDO**

- o pending de fingerprint precisa atravessar fingerprints já existentes antes de encontrar o próximo
  gap; o custo aumenta conforme o backlog avança;
- o fallback por foto multiplica criação de processos FFmpeg;
- location rebuild fará várias queries bbox por coordenada, embora geometries sejam cacheadas.

**HIPÓTESE**

- índice/pending queue materializada poderia reduzir o crescimento do anti-join;
- separar arquivos problemáticos do grouped FFmpeg pode preservar batch para os demais.

## L. Invariantes realmente provadas

| Requisito | Implementação | Prova discriminante | Runtime |
| --- | --- | --- | --- |
| Um claim pertence a um Worker/tentativa | Queue predicates/fence | `ExecutionQueueIntegrationTest`, `ExecutionReclaimRuntimeRecoveryIntegrationTest` | Estado real tem owner+lease no running |
| Dois claimers não levam a mesma execução | `SKIP LOCKED` | Teste com claimers concorrentes | Índices presentes |
| Regra 1+1 | Índices parciais | Queue dedup/race integration tests | Zero duplicações pending/running |
| Escrita tardia não alcança tentativa nova | `claim_count` pin | Reclaim runtime integration | Coerente |
| Content change pela porta é atômico | `@Transactional` + SQL CAS | `ContentChangeRollbackIntegrationTest` | Não há eventos de conteúdo reais para confrontar |
| Fingerprint tardio é recusado | `insertForRevision` | `ContentGenerationGuardsIntegrationTest` | Estrutura presente |
| Location/event pela porta é atômico | SQL functions | `CatalogLocationWriterIntegrationTest` | Único evento real coerente |
| Um path não tem dois `ACTIVE` no estado atual | Portas + matcher | Integration tests | Zero grupos duplicados |
| Catálogo atual corresponde ao disco | Inventory/reconcile | — | 146.217 `ACTIVE` presentes; 1 `MISSING` ausente |
| Derived result não aparece pela metade | publication protocol | Testes PostgreSQL citados no ADR | Estrutura presente |

## M. Invariantes apenas assumidas

- Todo escritor de conteúdo passa por `ContentReconciliation`: **falso**.
- Todo lifecycle relevante deixa evento: **falso**.
- Eventos são imutáveis: somente convenção.
- Histórico sobrevive à vida da projeção: **falso**.
- Todo fingerprint representa a revision atual: sustentado indutivamente em alguns fluxos, mas quebrável
  pelo Inventory.
- O Worker sempre volta após uma falha: **NÃO PROVADO**.
- O RDCW/USN nunca perde uma transição nas condições suportadas: **NÃO PROVADO**.
- `RUNNING` sempre significa atividade corrente: falso sem consultar lease; o código de leitura já conhece
  essa distinção.
- A suite inteira está verde: **NÃO PROVADO**.
- Os 40.402 fingerprints reais descrevem os bytes atuais: **NÃO PROVADO**, pois a tabela não guarda
  revision.

## N. Componentes que manteria

- Separação App × Worker e os três casos excepcionais da App.
- PostgreSQL como fila durável e ponto de coordenação.
- Deduplicação 1+1 por índices parciais.
- Lease, `claim_count` e fencing.
- Locks de path antes de consumir tentativa.
- `CatalogLocationWriter` e funções SQL de location.
- UUIDv7 como chave idempotente de fatos/operações.
- Separação entre identidade lógica e filesystem identity.
- `MISSING` distinto de `DELETED`.
- Reconcile conservador; não inventar rename.
- Inventory como único descobridor de newcomers.
- Publicação/supersessão de resultados derivados.
- Authority central de progress/ETA por `ExecutionType`.
- Batching de paths com `unnest`.
- Keyset pagination do reconcile.
- Bbox + JTS prepared geometry sem carregar o mundo em memória.
- Testes de integração PostgreSQL e testes de corrida da queue.

## O. Componentes que redesenharia

1. Conteúdo como entidade versionada explícita.
2. Inventory como observador, não escritor paralelo de conteúdo.
3. Estado atual de presença/localização em uma única projeção relacional com unique parcial.
4. Journal completo, append-only e não dependente da vida da projeção.
5. Eventos de quarantine/restore/delete com vocabulário uniforme.
6. Purge como tombstone, não cascade de história.
7. Fingerprints/metadata ligados à versão de conteúdo.
8. Roles PostgreSQL distintas para owner/migration e runtime.
9. Constraints de execution status/lease.
10. Worker health visível na tela de execuções.
11. Tentativas de execução historicamente registradas, caso auditoria multi-worker seja requisito.

## P. Proposta de arquitetura alvo

### Conceitos centrais

```text
Library
  └─ CatalogFile                         identidade lógica estável
       ├─ CatalogPresence                estado atual + último path
       ├─ ContentVersion 1..N            versões imutáveis do conteúdo
       │    ├─ MediaMetadata
       │    ├─ Fingerprint
       │    └─ GeoLocation
       └─ CatalogEvent 1..N              journal append-only

Execution
  ├─ ExecutionAttempt 0..N               claim/owner/lease/finalização
  ├─ Steps/Errors/Metrics
  └─ PublishedResult                     planos, previews, groupings

FileOperation
  └─ Movement                            intenção/resultado físico
       └─ links to CatalogEvent
```

### Regras

- `CatalogFile` identifica a linhagem reconhecida pelo produto.
- `CatalogPresence` é a única authority do lifecycle e último path.
- `ContentVersion` é criada sempre que um digest diferente é confirmado.
- Derivados apontam para `content_version_id`, não apenas para o arquivo.
- Uma mudança de conteúdo cria versão; nunca sobrescreve silenciosamente a anterior.
- Inventory, watcher, secure move e metadata rebuild produzem `ContentObservation`; somente um
  `ContentTransitionService` decide.
- `CatalogEvent` registra ao menos:

  - `CATALOGUED`;
  - `PATH_CONFIRMED`;
  - `RENAMED`;
  - `MOVED`;
  - `MISSING`;
  - `REAPPEARED`;
  - `CONTENT_CHANGED`;
  - `QUARANTINED`;
  - `RESTORED`;
  - `DELETED`;
  - `PURGED`.

- Projeção e evento mudam na mesma transação.
- Journal não é deletado quando a projeção é purgada.
- Não recomendo event sourcing integral. A projeção atual continua sendo a leitura eficiente; o journal é
  auditoria e evidência, não a única forma de reconstruir tudo.
- Execution atual pode continuar como protocolo central. `ExecutionAttempt` seria um complemento de
  auditoria, não uma substituição da queue.

### Frontend/backend

- Frontend lê somente DTOs de projeção.
- Toda mutação responde com execution ou operação durável.
- Worker health e reclaim pendente aparecem na tela de execuções.
- “Histórico completo” só deve ser exibido quando existir `CATALOGUED` ou um evento explícito `BASELINED`;
  nunca inferido.

## Q. Mudanças de schema recomendadas

1. Criar `library`, ainda que inicialmente singleton.
2. Reduzir `catalog_file` a identidade estável e ponteiros de estado.
3. Criar `catalog_presence`:

   - `catalog_file_id`;
   - lifecycle;
   - current/last-known path;
   - canonical key;
   - filesystem identity;
   - lifecycle timestamp;
   - unique parcial para paths `ACTIVE`.

4. Criar `content_version`:

   - ID/UUID;
   - catalog file;
   - monotonically increasing revision;
   - SHA-256;
   - size;
   - observed modified time;
   - observation provenance;
   - unique `(catalog_file_id, revision)`.

5. Migrar metadata/photo/video/fingerprint/geolocation para `content_version_id`.
6. Incluir revision/family completa na chave dos fingerprints.
7. Tornar `catalog_event` independente de hard-delete:

   - FK `RESTRICT`, ou UUID sem cascade;
   - snapshot mínimo de path/content revision;
   - revogar `UPDATE`/`DELETE` do runtime.

8. Movements devem guardar UUID/snapshot do arquivo e não desaparecer por cascade.
9. Introduzir tombstone para purge.
10. Constraints de execution:

    - `RUNNING` exige owner, claimed_at e lease;
    - `PENDING` não pode ter owner/lease/finished_at;
    - terminal exige `finished_at`;
    - progress não negativo;
    - rate window coerente.

11. Criar role de migration/owner e role runtime limitada.
12. Opcional: `execution_attempt` para preservar owners, claim counts e causas de reclaim.

## R. Dados descartáveis/reconstruíveis no breaking change

Podem ser descartados e reconstruídos:

- catálogo atual e locations;
- metadata/photo/video;
- fingerprints e failures/tasks;
- similarity relations/coverage/groupings;
- geo boundaries/cache/media geolocation;
- organization preview;
- metadata preview;
- self-written paths;
- métricas derivadas;
- thumbnails/cache de arquivos auxiliares.

Devem ser preservados ou exportados antes:

- usuários/credenciais;
- settings/preferências;
- exclusions de pasta;
- exclusions de arquivo, com estratégia de remapeamento;
- quarantine ainda restaurável;
- movements necessários para undo;
- executions/steps/errors históricos;
- access logs;
- eventos existentes;
- conversion results relevantes.

Como file exclusions atuais dependem de catalog ID/revision, uma reinventory destrutiva precisa exportá-las
por uma identidade remapeável: UUID, digest conhecido e último path. Ambiguidades devem ficar como
“unresolved user decision”, não ser silenciosamente descartadas.

## S. Plano conceitual de migração atual → alvo

1. Congelar mutações e criar backup físico/lógico.
2. Exportar dados insubstituíveis e decisões de usuário.
3. Criar o schema alvo em paralelo.
4. Migrar users/settings/preferences/access audit.
5. Migrar executions/movements/eventos atuais para um namespace histórico ou formato legado.
6. Criar `Library`.
7. Reinventory total, gerando `CATALOGUED` para arquivos encontrados.
8. Para dados antigos sem descoberta real, gerar apenas `BASELINED`, sem inventar data ou origem.
9. Construir primeiras `ContentVersion`.
10. Remapear exclusions por UUID/digest/path, retendo ambiguidades.
11. Recalcular metadata, fingerprints, similarities e geolocation.
12. Validar:

    - files físicos × active presence;
    - uma ocupação ativa por canonical path;
    - cada derivado ligado à content version atual;
    - nenhum event/movement órfão;
    - execution constraints;
    - cobertura das decisões de usuário migradas.

13. Cortar leitura/escrita para o novo schema.
14. Manter o schema antigo read-only por uma janela de validação.
15. Remover somente estruturas regeneráveis antigas.

## T. Ordem sugerida das próximas fases

1. **Corrigir conceitualmente a authority de conteúdo** e escrever testes de Inventory com bytes
   diferentes. Esse é o único P0.
2. **Decidir o contrato de histórico**: transições parciais ou audit journal completo. A documentação
   atual promete o segundo.
3. Desenhar `ContentVersion`, `CatalogPresence` e o modelo de eventos alvo.
4. Definir política de purge/tombstone e retenção de movements/events.
5. Especificar schema vNext e constraints.
6. Especificar migração de dados insubstituíveis, especialmente exclusions e quarantine.
7. Fortalecer provas:

   - Inventory content change end-to-end;
   - reappearance unitário com rollback;
   - quarantine/restore lifecycle journal;
   - purge preservando audit;
   - DB permissions/immutability;
   - integração Windows real em ambiente dedicado.

8. Tratar observabilidade de Worker/reclaim.
9. Medir e corrigir o fallback grouped FFmpeg.
10. Só então implementar a breaking migration e executar reinventory/rebuild.

## Evidências do PostgreSQL e runtime reais

Todas as consultas foram executadas em transações `READ ONLY` no PostgreSQL 17.6 local.

### Cardinalidades relevantes

- `catalog_file`: 146.218;
- `catalog_file_location`: 146.218;
- `catalog_file_event`: 1;
- `media_metadata`: 146.218;
- `photo`: 123.049;
- `video`: 5.860;
- `media_fingerprint`: 40.402;
- `execution`: 60;
- `geo_admin_boundary`: 52.785;
- `media_geo_location`: 0;
- `movement`: 0;
- migrations Flyway: 58, todas aplicadas com sucesso.

### Invariantes observadas

- 146.217 `ACTIVE` apontavam para arquivos fisicamente existentes;
- zero `ACTIVE` apontavam para arquivo ausente;
- o único `MISSING` apontava para arquivo ausente;
- zero paths canônicos tinham mais de um ocupante `ACTIVE`;
- zero arquivos ativos estavam sem location;
- zero execuções `RUNNING` estavam sem owner/lease;
- zero execuções terminais estavam sem `finished_at`;
- zero `PENDING` mantinham owner/lease;
- todas as 146.218 revisões de conteúdo eram `1`;
- 32.535 metadata rows tinham GPS e nenhuma tinha `media_geo_location`.

### Estado operacional observado

- `FINGERPRINT_PHOTO` ID 54: `RUNNING`, lease expirado, 4.525/87.985 concluídos;
- `FINGERPRINT_VIDEO` ID 55: `PENDING`;
- último heartbeat Worker observado cerca de quatro horas antes da auditoria;
- logs mostravam reclaims anteriores funcionando e fallbacks repetidos do FFmpeg agrupado.

## Conclusão

A fundação de execução, concorrência e localização merece ser preservada. O centro que precisa ser refeito
é o modelo de conteúdo vigente e o contrato de histórico. A arquitetura atual está próxima de um bom
modelo de projeção + fatos, mas ainda não satisfaz a afirmação de “uma única authority” nem a promessa de
“full audit history”.