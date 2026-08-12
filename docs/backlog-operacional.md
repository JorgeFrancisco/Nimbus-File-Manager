# Backlog operacional — aceitação, ciclo de vida e limitações conhecidas

O que resta fazer, avaliar ou deliberadamente não fazer **em torno da arquitetura que já existe**.
Nada aqui reabre uma decisão arquitetural: como App e Worker funcionam está em
[`architecture/worker-architecture.md`](architecture/worker-architecture.md), e o porquê está nos
ADRs 0003 a 0008.

Este documento é separado de [`evolucao-arquitetura-e-produto.md`](evolucao-arquitetura-e-produto.md)
de propósito. Aquele é uma lista de **sugestões** de para onde o produto pode ir; este é o que
sobrou de operar o que já foi construído — aceitação, empacotamento, e as coisas que se decidiu não
fazer e que não devem voltar como defeito na próxima auditoria.

**Três categorias, e a diferença entre elas importa:**

| Categoria | O que significa |
| --- | --- |
| **PENDÊNCIA** | precisa ser feito ou validado; a ausência é dívida |
| **MELHORIA CANDIDATA** | avaliar custo/benefício antes de fazer; não fazer não é dívida |
| **DECISÃO / LIMITAÇÃO CONHECIDA** | deliberadamente não é para agora; **não é defeito** e não deve ser reportado como tal |

Item concluído sai daqui, como no documento de evolução — histórico é assunto do git.

---

## 1. Aceitação / QA

**PENDÊNCIA — aceitação manual dos `ExecutionType`.** O caminho a validar em cada um é o mesmo:
tela → `Execution` enfileirada → Worker reivindica → efeito real no disco/catálogo → status terminal
com mensagem legível.

Priorizar por evidência, não por ordem alfabética. No banco real do desenvolvimento, três tipos já
atravessaram o dispatcher de ponta a ponta (`INVENTORY`, `RECONCILE`, `GEO_DATASET_UPDATE`) e
`FINGERPRINT_PHOTO`/`FINGERPRINT_VIDEO` têm prova em Testcontainers. Os demais têm cobertura de
unidade e de integração parcial, mas **nenhuma execução real registrada** — e foi exatamente essa
diferença que escondeu um defeito por uma fase inteira. A consulta que responde quem já rodou de
verdade é a que distingue `claimed_by IS NOT NULL AND claim_count > 0` de linha migrada.

**PENDÊNCIA — o piso de cobertura.** Instrução, linha e método seguem alguns centésimos abaixo do
piso registrado no README, com o resíduo declarado ali. Regravar o piso exige o procedimento
*Recalcular o piso* do `AGENTS.md`, que é decisão deliberada e não efeito colateral de tarefa.

## 2. Empacotamento e ciclo de vida do processo

**PENDÊNCIA — aceitação do produto instalado.** Nada disto é exercitável a partir do código-fonte:
depende do MSI, de serviços do Windows e de reinícios de máquina.

- App supervisionando o Worker na instalação real;
- Worker morto → restart → reclaim de startup;
- shutdown coordenado, e saída pela bandeja;
- atualização com Worker vivo, e desinstalação com Worker vivo;
- *repair* do instalador;
- Worker antigo não pode sobreviver a um upgrade;
- dois Workers não podem coexistir indevidamente;
- falha ao iniciar o Worker, incluindo o estado em que a supervisão desiste;
- PostgreSQL embarcado ao longo de todos esses ciclos;
- aceitação manual do MSI.

## 3. Experiência das execuções

**MELHORIA CANDIDATA.** A fila é sólida; o que se vê dela ainda não é uniforme.

- um `202 Accepted` deve levar claramente à `Execution` criada;
- mensagens acionáveis: dizer o que fazer, não só o que houve;
- dizer **por que** uma execução está esperando. Que ela espera já aparece: a faixa global mostra
  quem está na fila e, ao passar o mouse, quem está à frente. O que falta é o motivo — contenção de
  caminho, limite de concorrência do tipo — em vez de o usuário deduzir pela lista;
- oferecer cancelamento **apenas onde ele é suportado** (§5 da arquitetura), em vez de um botão que
  às vezes não faz nada;
- reexecutar um pedido, onde isso fizer sentido;
- pedir a atualização da faixa no instante em que uma tela enfileira algo por `fetch`, em vez de
  esperar o intervalo. Hoje toda tela que enfileira recarrega ou navega, e a faixa consulta assim
  que a página abre — o intervalo só aparece nos fluxos que não recarregam, onde a espera é de
  poucos segundos. O gancho seria um evento no `document`, para que nenhuma tela precise conhecer o
  endpoint.

Já resolvido, e registrado aqui só para não voltar como pendência: **experiência consistente para
qualquer execução em andamento**. Uma faixa única, em todas as telas, alimentada por
`/api/execution-activity`; as duas parciais que existiam (`/api/background-job` e o atributo de
modelo resolvido na renderização) foram aposentadas.

## 3.1 Similarity — escala e geração global de candidatos

**RESOLVIDO para FOTO — o teto por execução deixou de existir.** A análise de semelhança de fotos
considera a biblioteca elegível inteira. Vídeo mantém seu `maxCandidates` configurável e continua parcial
em acervos grandes; os dois caminhos são independentes de propósito.

O que a medição mostrou, e que retirou a razão de ser do teto (`SimilarityProductionPathSpike`,
acervo real de 119.830 fotos, raio 96, SSIM ≥ 95):

| fase | tempo | heap ao final |
| --- | --- | --- |
| carga (hashes + amostras, na forma das linhas de produção) | 5,78 s | 469 MB |
| varredura de Hamming (7,18 bilhões de pares) | 20,50 s | 360 MB |
| SSIM sobre 1.333.420 candidatos + relações | 6,27 s | 362 MB |
| agrupamento (11.481 grupos, 26.390 arquivos agrupados) | 0,06 s | 328 MB |
| **total** | **32,60 s** | **pico observado 469 MB** |

O pico é **11,4% do `-Xmx4g` do Worker**, que é onde este caminho roda. A estrutura de relações
ocupa 0,9 MB. O teto foi introduzido como proteção de memória e a memória nunca foi o problema: o
que ele fazia, na prática, era **truncar 93% do acervo**.

Como a remoção foi feita:

- a query de elegíveis passou a aceitar `LIMIT NULL` — a ausência de limite, na leitura do próprio
  PostgreSQL — em vez de ganhar uma segunda cópia do predicado de exclusão, que é sutil e já custou
  um defeito. Vídeo continua passando seu teto para a mesma query. Provado contra PostgreSQL real em
  `SimilarityCappedSelectionIntegrationTest`;
- `candidateLimit` **saiu do `parametersDigest`** de foto. O digest muda, então toda resposta
  publicada sob o teto vira família própria e deixa de ser lida — o que é o desfecho honesto, porque
  aqueles resultados *eram* sobre 8.000 arquivos. Custa uma análise completa, uma vez;
- o `ACTIVE` antigo **não é apagado nem rebaixado**: ele simplesmente pertence a outra família e não
  é mais encontrado. O `supersedeActive` só age dentro da família nova, na promoção do novo
  `BUILDING`, então a resposta anterior continua servindo a tela até o instante em que a nova é
  publicada;
- as **relações e a cobertura sobrevivem**: são chaveadas por `(algorithm_id, max_distance,
  min_similarity)`, e não pelo digest, exatamente para que exclusão e ajuste de recorte não
  descartassem fatos que não mudaram. Os 8.000 arquivos já cobertos continuam cobertos.

O que **continua** valendo, e não é pendência:

- **lote independente não é solução.** Semelhança é uma relação global: dois arquivos separados pela
  fronteira do lote nunca seriam comparados, e o complete-linkage sobre lotes isolados estaria errado
  por construção;
- a **geração global de candidatos indexada** (índice sobre o pHash em vez de all-pairs) continua
  sendo o caminho para acervos muito maiores que este. Deixou de ser urgente: 33 s e 469 MB para 120
  mil fotos não é um problema a resolver hoje. As propriedades que já estão certas seguem valendo:
  `BUILDING` invisível, `ACTIVE` anterior intacto até a promoção, índice parcial garantindo um
  `ACTIVE` por família, e cancelamento sem publicar
  parcial.

**PENDÊNCIA — calibração pHash × SSIM.** O agrupamento atual enumera pares em O(n²) sobre o conjunto
carregado: a complexidade vem do algoritmo, não do raio. O raio
`MAX_PHASH_CANDIDATE_DISTANCE = 96` em 256 bits (37,5% do hash) **agrava** o problema por outro
caminho — sendo amplo, ele poda pouco, e uma vizinhança que cobre 37,5% do espaço inviabiliza as
estratégias simples de *blocking*, que dependem de o raio ser pequeno em relação ao hash.

Antes de escolher a estrutura de índice é preciso medir, no acervo real, qual raio preserva os pares
que o SSIM confirma. A escolha entre *banding*/multi-index hashing e BK-tree depende desse número: um
raio efetivamente necessário de 24/256 e um de 80/256 levam a desenhos completamente diferentes.

> A bateria de calibração que produziu os números abaixo (amostragem de pares, varredura de Hamming,
> histogramas por faixa, estatísticas de luminância) **foi removida no cleanup de P&D**: ela
> respondeu à sua pergunta, o raio 96 e o threshold entraram no código, e um museu de spikes custa
> manutenção sem proteger comportamento. Se a escolha do índice voltar à mesa, a instrumentação se
> reescreve a partir dos carregadores read-only que ficaram (`CalibrationHashes`,
> `CalibrationSamples`) — o caro era a decisão, não o código.

**Ressalvas metodológicas da calibração — valem para toda leitura dos números medidos.**

1. **Um acervo, não o produto.** Os resultados vêm de um único acervo real e de amostras dele. Não
   validam thresholds globais para todos os usuários: a composição do acervo (proporção de
   *screenshots*, rajadas, documentos digitalizados, fotografia de estúdio ou noturna) muda o que
   cada faixa contém. A formulação correta é *"na amostra do acervo analisado, não foram observados
   pares com SSIM >= 90 fora do raio 96; isso é evidência local, não validação global do
   threshold"* — e não "96 está validado" nem "90/95 não perde nada".
2. **Ausência observada tem poder estatístico limitado.** Um par só entra na amostra se os dois
   arquivos forem sorteados, então a amostra preserva apenas o quadrado da fração de arquivos —
   0,17% dos pares para 5.000 de ~120 mil. "Zero casos observados" é compatível com centenas de
   casos na biblioteca inteira; zero na amostra não é zero na biblioteca.

**Achado de P&D — o custo do agrupamento é estrutural, não das métricas.** Medido sobre a biblioteca
inteira (119.813 fotos, raio 96, SSIM >= 95, chamando o `SimilarityCompleteLinkageGrouper` de
produção):

- o agrupamento faz **~6,37 bilhões** de chamadas a `score()` — 88,8% de todos os pares possíveis —
  porque cada foto sem grupo é comparada contra um representante de cada cluster já formado, e os
  clusters são dezenas de milhares. Custa **351 s**, contra ~24 s para calcular as mesmas relações
  uma única vez;
- a memoização de `score()` teve **zero acertos** durante o agrupamento, com ~68 MB de footprint:
  cada par é perguntado no máximo uma vez, porque o candidato para no primeiro cluster compatível.
  O memo só rende depois, no `worstScore` de cada grupo;
- por parar no primeiro cluster compatível, o algoritmo é **guloso e dependente da ordem** — uma foto
  entra no primeiro grupo aceitável, não no melhor —, e 202.510 pares dentro do raio nunca chegam a
  ser avaliados;
- a latência de cancelamento **não** é problema: 119.813 checkpoints, intervalo médio de 2,9 ms e
  pior caso de 0,06 s.

**Comparação feita, e implementada.** O agrupamento passou a consumir um **grafo esparso de relações
já aprovadas** (`ApprovedRelations`, adjacência comprimida) em vez de chamar `score()` de dentro do
laço; `SimilarityRelationGrouperTest` prende os dois agrupamentos ao mesmo resultado. As relações
aprovadas ficaram **duráveis** (`similarity_relation`, V26), chaveadas por
`(algorithm_id, max_distance, min_similarity)` — e não pelo `parametersDigest`, porque exclusão e
política de seleção decidem *quais arquivos entram*, não se dois arquivos se parecem.

**Incrementalidade — o que existe e o que falta.**

- **REMOVE está implementado.** `SimilarityRunMode.REGROUP` reagrupa a partir das relações
  sobreviventes, sem Hamming e sem SSIM, e publica pelo protocolo de sempre (`BUILDING` invisível →
  promoção curta). Só os arquivos que participam de alguma relação são carregados: quem não tem
  vizinho aprovado só formaria grupo de um e nunca é o motivo de outro candidato ser recusado.
- **O reagrupamento inteiro roda de novo, de propósito.** A colocação é gulosa, então um arquivo pode
  ser a razão de outro ter sido recusado; editar os grupos publicados manteria a recusa para sempre.
  O contraexemplo (A~B, A~C, B≁C: remove B e o certo é `{A, C}`) está preso em
  `SimilarityIncrementalEquivalenceTest` e em `PhotoSimilarityRegroupTest`.
- **A promoção é guardada.** Um regroup só substitui o `ACTIVE` de que ele *derivou*
  (`publishIfStillBasedOn`, `UPDATE` condicional pelo id). Se um rebuild publicou no meio do
  caminho, o regroup é descartado — ele tiraria de circulação uma resposta mais completa.
**Medido no acervo real (119.830 fotos, raio 96, SSIM >= 95).** O spike que produziu esta tabela foi
removido no cleanup de P&D — a decisão que ele sustentava (relações duráveis, writer JDBC, REGROUP)
já está no código e protegida por testes; os números ficam aqui como **histórico de medição**, não
como contrato. Duas execuções, uma com o JaCoCo ligado e outra sem, porque o baseline anterior de
~37 s não
registrava em qual regime foi tirado. As contagens saíram idênticas nas duas — 1.333.420 pares dentro
do raio, 31.747 relações, 11.481 grupos, 26.630 arquivos participando de alguma relação —, o que
confirma que só os tempos variaram.

| fase | com JaCoCo | sem JaCoCo |
| --- | --- | --- |
| análise completa (scan + SSIM + clustering) | 77,4 s | 50,0 s |
| persistir 31.747 relações (32 idas de 1.000) | 5,6 s | 5,0 s |
| escrever o agrupamento como BUILDING | 34,4 s | 43,0 s |
| promover (retirar + publicar) | 0,07 s | 0,05 s |
| REGROUP: ler + indexar + carregar + clusterizar | 4,2 s | 5,1 s |
| REGROUP total | 35,3 s | 38,3 s |

Três leituras, e a terceira é a que muda o próximo passo:

- **A durabilidade saiu barata.** 5 s e 7,5 MB para 31.747 relações. Era o risco da tabela nova e ele
  não se materializou.
- **A incrementalidade entrega o que prometia, no trecho que ela cobre.** O trabalho que o REGROUP
  substitui custa 4-5 s contra 50-77 s de análise, de dez a dezoito vezes menos.
- **O gargalo mudou de lugar: passou a ser escrever o agrupamento** — e foi corrigido logo em
  seguida (abaixo).

Ressalva: as fases ligadas a banco e I/O variaram até 30% entre as duas execuções, com a aplicação
rodando ao lado e disputando CPU. O que é estável e comparável são as contagens e a ordem de
grandeza, não o segundo exato. Medido depois, com a máquina livre, a análise deu 38 s: **os 77 s da
primeira corrida eram contenção, não regressão**, e o baseline de ~37 s se reproduz.

**A materialização do BUILDING, medida e corrigida.** `SimilarityPublisher.build` gastava 31-43 s
para 11.481 grupos e 26.630 membros. A causa saiu do `pg_stat_statements` e das estatísticas do
próprio Hibernate, não de suposição:

| | JPA (antes) | writer JDBC |
| --- | --- | --- |
| relógio de parede | 37,5 s | **3,4 s** |
| inserts de entidade × statements preparados | 38.113 × 38.113 | 2 × 2 |
| tempo dentro do servidor | 3,9 s | 2,0 s |
| **tempo fora do servidor** | **33,6 s** | **1,4 s** |
| linhas e tamanho em disco | 11.481 / 26.630 · 6496 kB | idêntico |

Um insert preparado por insert executado: **não havia lote nenhum**. E não era configuração ausente —
as três entidades usam `GenerationType.IDENTITY`, e o Hibernate precisa da chave gerada antes de
guardar a entidade, então não pode adiar o insert. Definir `hibernate.jdbc.batch_size` não teria
mudado nada. Era também o que fazia obter o id de cada grupo custar uma ida e volta: 11.481 grupos,
11.481 chamadas.

`SimilarityGroupingWriter` faz JDBC em lote, no mesmo molde do `SimilarityRelationWriter`, e
**reserva** os ids dos grupos numa ida só (`nextval` sobre `generate_series`) em vez de recebê-los um
a um. Os membros não reservam nada, porque ninguém aponta para eles. Nada mudou na estratégia global
de ids: as entidades mantêm o mapeamento e a sequence é a que o `BIGSERIAL` criou. A ordem de leitura
da tela vem de `position`, nunca do id, o que é o que torna a reserva segura.

No acervo real o BUILDING caiu de 31-43 s para **2,4-2,5 s**, e com ele o REGROUP inteiro de 35-38 s
para **5,8 s**. Com a persistência nessa ordem de grandeza, **o desenho continua simples**: regroup
gera um BUILDING completo e promove atomicamente. Copy-on-write e versionamento parcial ficam
descartados enquanto o número for este.

**ADD incremental — implementado.** O que faltava era saber, a partir da tabela de relações, *quais
arquivos* um conjunto de relações cobriu: só aprovações são gravadas, então um arquivo analisado que
não casou com nada não aparecia em lugar nenhum. `similarity_relation_coverage` (V27) responde isso
com uma linha por arquivo incorporado, chaveada por `(algorithm_id, max_distance, min_similarity)` —
as mesmas três colunas da tabela de relações, e pelo mesmo motivo.

O que uma linha de cobertura significa: **o arquivo faz parte do universo relacional daquela
configuração** — todo par entre ele e todo outro arquivo coberto já foi avaliado, qualquer que tenha
sido o veredito. Não significa "elegível hoje": a cobertura **sobrevive** a exclusão, quarentena e
deleção lógica, porque nenhuma delas muda o que duas imagens parecem. É isso que torna o retorno de
um arquivo gratuito em vez de uma recomputação.

A regra que isso impõe ao ADD, e o contraexemplo que a justifica:

- os novos `N` são comparados contra o conjunto **coberto** `C` e entre si — `N × C + N × N`, nunca
  `C × C`;
- comparar contra o conjunto **elegível** deixa um buraco que nenhum flag por arquivo registra: se A
  está coberto mas oculto quando B chega, B pula A, os dois terminam cobertos, e o par A–B nunca é
  avaliado por ninguém. `SimilarityCoverageModelTest` prende esse contraexemplo e as nove situações
  que a regra tem de sobreviver;
- `C × C` não é perguntado porque a resposta não pode ter mudado: parecer-se é fato sobre os dois
  arquivos, e nenhum deles foi tocado. Um par cujo veredito poderia ter mudado tem um arquivo
  refingerprintado dentro, e esse arquivo **não** está coberto — `forget` apaga relações e cobertura
  juntas, e ele volta para `N`.

Ordem transacional: **relações primeiro, cobertura depois, na mesma transação**. Conceder cobertura
antes transformaria uma queda em buraco silencioso permanente (o arquivo apareceria incorporado e
nunca mais seria comparado); a ordem inversa é apenas desperdício — o arquivo continua novo e a
repetição faz upsert sobre as mesmas linhas.

Carga em dois passos, e é o que faz o ADD valer a pena: o scan de distância precisa dos hashes de
toda a biblioteca (32 bytes cada, 3,7 MB medidos) e o SSIM precisa das amostras (1 KB cada, 117 MB
para a biblioteca inteira) **apenas dos pares que sobreviveram ao raio** — 16 MB no maior lote
medido. Carregar linhas do jeito do rebuild gastaria o custo de leitura de um rebuild para evitar um
rebuild.

**Gatilho e coalescing.** O `dedup_key` de um ADD nomeia **só a família** (mídia, algoritmo, versão,
`parametersDigest`) e termina em `ADD` — sem o digest de composição que a chave de rebuild carrega.
Essa única diferença é o que impede um backup de celular de publicar uma vez por foto: um rebuild é
sobre um instantâneo, então dois pedidos sobre instantâneos diferentes são trabalhos diferentes; uma
chegada é sobre *o que for novo quando ela começar*, então dois pedidos são o mesmo trabalho.

O coalescing é da fila, não de um timer — a regra 1 + 1 já permite exatamente um pedido esperando
enquanto um roda, então fotos que chegam durante a execução se juntam no único sucessor, e fotos que
chegam enquanto esse sucessor espera são absorvidas por ele. Nada se perde e nada é reagendado. O
`PhotoFingerprintJobHandler` pede um ADD por *threshold já analisado* quando um drain escreveu
fingerprints — os thresholds saem da cobertura, porque o threshold é preferência de tela por usuário
e não há usuário no worker; e não se pede nada antes da primeira análise, porque não existe resposta
para uma chegada trazer em dia.

**Equivalência com o rebuild, provada.** `PhotoSimilarityAddTest` (14 cenários) e
`PhotoSimilarityAddEquivalenceTest` (60 intercalações aleatórias, mais uma chegada por vez) rodam o
caminho de produção — distâncias, SSIM, colocação gulosa, cobertura — sobre um banco simulado em
memória e comparam o resultado com o **rebuild completo do mesmo conjunto final**, calculado pelas
mesmas classes. Nenhuma expectativa escrita à mão: a resposta certa é a que o rebuild dá.

**Defeito encontrado nessa fatia.** `PhotoSimilarityService` é `@Transactional(readOnly = true)` na
classe, e no PostgreSQL isso não é dica — chega na conexão como `SET TRANSACTION READ ONLY` e o
driver recusa todo insert feito por baixo, relações inclusive. Ou seja: **o rebuild nunca conseguiu
gravar relações através do serviço**; as medições anteriores chamavam o writer direto e por isso não
viram. `analyze` e `add` passaram a `NOT_SUPPORTED` — a análise roda fora de transação e o writer
abre a sua, curta, que é onde relações e cobertura ficam atômicas. `SimilarityAddIntegrationTest`
roda contra PostgreSQL real e **sem** `@Transactional`, de propósito: envolver o teste tornaria as
fronteiras dele, e não as do produto.

### Benchmark do ADD no acervo real

`SimilarityAddCostSpike`, opt-in, leitura pura sobre a biblioteca de **119.830 fotos** (raio 96,
SSIM ≥ 95). Rebuild e chegada medidos no mesmo processo, minutos um do outro, porque segundos
absolutos variam com contenção e razão medida em execuções distintas já foi lida como regressão
aqui.

Scan de distância do **rebuild completo**: **23,70 s** para 7.179.554.535 comparações (**3,30 ns**
cada), 1.333.420 candidatos dentro do raio.

| chegadas | comparações | hamming | candidatos | amostras lidas | I/O amostras | SSIM | total comparação | vs rebuild |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 119.829 | 9 ms | 8 | 9 (0,01 MB) | 71 ms | 9 ms | **88 ms** | 268× |
| 10 | 1.198.245 | 40 ms | 124 | 134 (0,13 MB) | 63 ms | 2 ms | **105 ms** | 226× |
| 100 | 11.977.950 | 60 ms | 1.791 | 1.712 (1,67 MB) | 110 ms | 21 ms | **192 ms** | 124× |
| 1.000 | 119.329.500 | 684 ms | 24.507 | 16.726 (16,3 MB) | 228 ms | 124 ms | **1,04 s** | 23× |

Relações aprovadas: 0, 0, 13 e 475. As fases que **escrevem** não entram nesta tabela e não foram
medidas aqui — medi-las significaria escrever no acervo. São as mesmas para os dois caminhos
(BUILDING 2,4–2,5 s, promoção 0,05–0,07 s) e, no caso das relações do ADD, ordens de grandeza
menores que as 31.747 do rebuild, que custaram 5 s.

Somando o que já estava medido, um ADD de 1.000 fotos custa ~1 s de comparação mais os ~5,8 s do
REGROUP completo (ler relações, carregar participantes, agrupar, BUILDING, promover) — **~7 s contra
os ~50,7 s do rebuild**.

### Crossover

Um rebuild compara `C × (C − 1) / 2`; uma chegada de `N` compara `N × (C − N) + N × N / 2`. Ao mesmo
custo por comparação — e é o mesmo laço, o mesmo layout de memória e os mesmos 3,30 ns —, as duas
fases se igualam em **N ≈ 59.914, metade da biblioteca**. Não há crossover em 1.000 nem perto disso:
a projeção vem das contagens medidas, não de intuição.

No **total** o crossover é ainda mais alto, porque o rebuild paga duas coisas que a chegada não
paga: os 117 MB de amostras de toda a biblioteca e o SSIM dos 1.333.420 candidatos. A política
prática que isso autoriza: **escolher ADD ou REBUILD pelo tamanho de `N`**, com o ponto de virada na
casa das dezenas de milhares — não em centenas, não em milhares.

### `MAX_CANDIDATES = 8000` — removido de FOTO

Os três pré-requisitos foram cumpridos: o pico de heap sobre a biblioteca inteira foi medido (469 MB
contra 4 GB), o efeito no digest foi decidido como comportamento desejado, e a primeira execução
após o upgrade passou a ser tratada como REBUILD por regra derivada. Detalhes na seção 3.1.

**A regra ADD × REBUILD, derivada e não escolhida.** As duas rotas produzem a mesma resposta, então
a única pergunta é custo, e o custo de ambas é dominado pela varredura de distância:

- uma chegada varre a biblioteca inteira **uma vez por recém-chegado** — `N × T` iterações, para
  `T = N + C`. Note que são *iterações*, não pares: os pares avaliados são `N × C + N × N / 2`, mas
  a varredura visita também os arquivos que pula, e é esse custo que aparece no relógio;
- um rebuild varre cada par uma vez — `T × (T − 1) / 2`.

A chegada é mais barata enquanto `N × T < T² / 2`, ou seja enquanto `N < T / 2` — e como `T = N + C`,
**enquanto os recém-chegados forem menos que os já cobertos**. O empate fica com a chegada, que ainda
soma às relações em vez de apagar e reescrever o conjunto da família.

A medição no acervo confirma a fórmula: 684 ms para 1.000 chegadas contra 23,70 s da varredura de
rebuild sobre as mesmas 119.830 fotos.

**No upgrade** — 8.000 cobertos, 111.830 não — a regra escolhe REBUILD sozinha, que é o certo: um ADD
nessa proporção faria 13,4 bilhões de iterações contra 7,18 bilhões do rebuild. Depois dele a
cobertura nomeia a biblioteca inteira e as chegadas seguintes voltam ao caminho incremental normal.

**Achado empírico — hipótese de P&D, não critério de produção.** Nos pares inspecionados manualmente
na faixa Hamming 97–128, o brilho médio **não** separou desejados de indesejados (a hipótese inicial
de "imagem escura = falso positivo" saiu refutada: os indesejados eram claros). A quantidade de
estrutura da amostra, aproximada pelo desvio-padrão dos 1024 bytes de luminância, mostrou separação
forte nos casos observados. Nada disso é critério de produção, e nenhuma regra foi derivada daí.

## 3.2 Teste intermitente — captura de log × reset do Logback

**BUG DE TESTE, aberto.** `SecureFileMoveTest.warnsWhenTheFileIsNotAtTheTargetOnceTheMoveReturned`
falha cerca de **uma vez a cada três execuções completas** e **nunca isolado** (12/12 repetidamente).
A asserção quebra assim:

```text
Expecting any elements of: [] to match given predicate but none did.
	at SecureFileMoveTest.warnsWhenTheFileIsNotAtTheTargetOnceTheMoveReturned(SecureFileMoveTest.java:103)
```

A lista de eventos volta **vazia** — indistinguível de um move que não registrou nada.

**Causa provada.** O appender vive no contexto Logback do processo inteiro, e um `reset()` desse
contexto **desanexa todo appender ligado programaticamente** (verificado por experimento: após
`LoggerContext.reset()`, `logger.isAttached(appender)` é falso). Quem reseta é o **Spring**: o
bytecode de `LogbackLoggingSystem.stopAndReset` chama `LoggerContext.stop()` e `reset()` a cada
inicialização do sistema de log, isto é, **a cada contexto Spring que sobe**. A suíte roda classes em
paralelo (`junit.jupiter.execution.parallel.mode.classes.default=concurrent`) e sobe ~50 contextos,
então a janela entre anexar o appender e ler a lista é real.

Descartado como causa: outra captura concorrente. Só **duas** classes usam `ListAppender`
(`SecureFileMoveTest` e `InventoryWatchServiceTest`) e elas capturam *loggers diferentes*. O
interferente é qualquer teste `@SpringBootTest`, não um par específico.

**Duas correções tentadas e refutadas por experimento:**

1. segurar `LoggerContext.getConfigurationLock()` em torno de anexar → agir → ler. **Não exclui
   nada**: o bytecode mostra que `stopAndReset` executa o `reset()` sem tomar lock algum;
2. um `LoggerContextListener` com `isResetResistant()` reanexando em `onReset`. **Não restaura a
   captura** nesta versão do Logback — o evento não é ponto de reanexação útil.

Nada foi alterado no teste além do Javadoc que registra isto. **Retry está descartado** (esconderia
tão bem um move que legitimamente não logou quanto um appender perdido) e serializar a suíte
(`@Execution(SAME_THREAD)`) ainda **não foi provado** como a solução mínima.

Próximos candidatos a investigar, quando a fatia chegar: impedir a reinicialização do Logback por
contexto (`org.springframework.boot.logging.LoggingSystem`, cuidando de não perder o
`logback-tests.xml`, que é aplicado justamente via `logging.config`); ou dar ao caso uma asserção que
não dependa do contexto global. Vale notar que `InventoryWatchServiceTest` tem a mesma exposição e
ainda não foi vista falhando.

## 3.3 Catraca candidata — quem muda o conjunto elegível anuncia que ele mudou

**MELHORIA CANDIDATA.** A regra já vale na prática e é verificada por leitura; o que não existe é o
teste que a impede de regredir.

**Formulação:** *todo mutador persistente do conjunto elegível deve alcançar `EligibilityChanged` por
uma boundary aprovada.*

Duas boundaries são legítimas hoje, e a formulação tem de acomodar as duas:

- `EligibilityAnnouncer`, que é o caminho de quase todos — quarentena, explorador, organização,
  reconciliação, inventário, troca de biblioteca, mudança de conteúdo, expurgo definitivo;
- **publicação direta de `EligibilityChanged`** pela própria operação, quando ela já sabe que a
  elegibilidade mudou. É o caso de `DuplicateExclusionService`: o anunciador o injeta para responder
  `repointCanChangeEligibility`, então mandá-lo passar por ali seria um ciclo, e editar a lista de
  exclusão é a única mudança de elegibilidade cujo publicador é a própria coisa consultada.

**Não** formular como "todo mutador depende de `EligibilityAnnouncer`": isso condenaria o segundo
caso, que é correto, e a auditoria da Fase 17 chegou a suspeitar dele por essa formulação antes de
descartar a suspeita.

O detector, quando a fatia chegar, parte de quem escreve: classes que alteram `duplicate_exclusion*`,
o *hard delete* de `catalog_file`, e as demais mutações conhecidas da elegibilidade — e verifica a
alcançabilidade de uma das duas boundaries. O que já é travado hoje está em
`EligibilityAnnouncementArchitectureTest`: quem pode construir o evento, que há um único consumidor,
e que nada chamado uma vez por arquivo detém o anunciador. O que falta é o outro lado — que ninguém
mude o conjunto **sem** dizer.

## 4. Observabilidade

**MELHORIA CANDIDATA.** Sem tabela nova: tudo abaixo é leitura do que já é durável.

- saúde do Worker visível fora do log;
- diagnóstico da fila (o que espera, e desde quando);
- motivo estruturado de espera, em vez de texto;
- duração por `ExecutionType`;
- tentativas, *retries* e *hand-backs* legíveis.

Já resolvido, e registrado aqui só para não voltar como pendência: **qual papel escreveu a linha**.
Cada processo tem o seu arquivo e o console traz `[APP]`/`[WORKER]`/`[COMBINED]`
([ADR 0009](adr/0009-um-arquivo-de-log-por-processo.md)).

## 5. Resiliência

**MELHORIA CANDIDATA — exercícios de caos.** Cada um responde a mesma pergunta: o catálogo e o disco
continuam de acordo depois?

Matar o Worker durante cada operação; matar o PostgreSQL; reiniciar a máquina; disco cheio; perder a
unidade da biblioteca no meio; arquivo movido por fora durante uma operação; concorrência entre pasta
pai e filha; FFmpeg ausente ou morto no meio de uma conversão.

**MELHORIA CANDIDATA — colisão de exclusão simultânea vira 500 em vez de idempotência.**
`DuplicateExclusionService.excludeFile` consulta e depois escreve, então dois pedidos simultâneos
sobre o mesmo arquivo passam os dois pela consulta. O **estado final é correto** — quem escreve por
último é recusado pela `UNIQUE (catalog_file_id)`, provada em
`DuplicateExclusionSchemaIntegrationTest` — e não há anúncio duplicado, porque quem falha faz
rollback e o ouvinte é `AFTER_COMMIT`. O que sobra é o mapeamento do erro: o segundo cliente recebe
500 onde a resposta honesta seria "já está excluído". Não é corrupção, e a constraint continua sendo
a autoridade certa; corrigir é traduzir a violação em idempotência, **não** tomar lock pessimista. A
prova concorrente vem antes da correção, e custa um contexto próprio: a que existe é transacional e
não consegue ver duas transações.

## 6. Política de recursos do FFmpeg

**PENDÊNCIA DE AVALIAÇÃO TÉCNICA.** Decidir com medição, não por uniformidade — e o risco a evitar
está no enunciado: **não degradar aceleração por GPU só para deixar o comportamento igual ao da CPU**.

Detecção de GPU; decoder/encoder por hardware; filtros; uso combinado de GPU e CPU; *fallback* para
CPU; número de threads; e como tudo isso se relaciona com o `concurrencyLimit` do handler.

## 7. Decisões e limitações conhecidas

Nenhum destes é defeito. Estão aqui para que uma auditoria futura os reconheça como deliberados.

**`GEO_DATASET_UPDATE` não é cancelável durante a aquisição.** Só antes de começar. O protocolo é
baixar → importar em transação → promover, e entre importar e promover não existe ponto seguro:
cancelar ali deixaria as tabelas descrevendo uma versão e o disco outra. Torná-lo cancelável exigiria
reescrever o protocolo de import. Ver §5 da arquitetura.

**Não há reclaim periódico.** A recuperação de execuções abandonadas roda no *startup* de cada papel.
Quem cobre o caso do Worker que morre com a App viva é a supervisão, que sobe outro — e o startup
dele roda o reclaim. Um segundo temporizador seria uma segunda política sobre as mesmas linhas.
Reavaliar só se a supervisão deixar de reiniciar.

**O 23505 do enqueue continua no log — só na corrida de verdade.** O caso determinístico saiu:
`enqueueOrExisting` pergunta se já existe uma esperando antes de inserir, então o pedido que a
recuperação devolveu à fila é reaproveitado sem tentativa de INSERT, e o boot deixou de parecer erro
de banco. O que resta é a corrida genuína — dois pedintes que não encontram nada e ambos inserem —,
onde o índice recusa um e a camada de persistência registra a violação em nível de erro antes de
qualquer código classificá-la. O comportamento está correto: uma execução, os dois pedintes
atendidos. Eliminar também esse ERROR exigiria escrever à mão o `INSERT` que hoje é gerado da
entidade, cujo modo de falhar é uma coluna nova esquecida no SQL, ou suprimir um logger que também
esconde violações reais. O motivo completo está em `ExecutionEnqueueService`.

**O estado do Worker não vai para a bandeja.** Decisão registrada no
[ADR 0008](adr/0008-operacoes-assincronas-da-app.md), com o caso estreito em que ela é mais apertada
do que parece: quando a supervisão desiste, o Worker não volta sozinho e o que informa isso é um
ERROR no log mais execuções paradas em `PENDING`.