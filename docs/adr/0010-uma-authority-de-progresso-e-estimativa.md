# ADR 0010 — Uma authority de progresso e de estimativa

## Status

Aceita.

## Contexto

A linha `execution` carrega quatro contadores (`files_found`, `files_analyzed`, `cache_hits`,
`errors`) e um total (`total_expected`), e **em nenhum lugar estava dito o que cada um significa**.
Quem decidia era a ordem dos argumentos que cada job handler passava ao serviço de progresso, e essa
ordem difere por workload:

| Workload | `files_found` é |
| --- | --- |
| INVENTORY | os arquivos **descobertos** (corre à frente da análise) |
| FINGERPRINT_* | o contador corrente de concluídos |
| SIMILARITY_* | o **total** |
| METADATA_REBUILD | **sempre 0** |
| CONVERSION | convertidos + pulados + erros |
| GEO_DATASET_UPDATE | etapas concluídas |

Isso era sobrevivível enquanto cada leitor pertencia a um workload. Deixou de ser quando surgiram
leitores genéricos — a tela de progresso, o banner de atividade e a estimativa servem todos os tipos
ao mesmo tempo. Eles tiveram que **adivinhar**, e adivinharam o mesmo campo:

- o rebuild de metadados ficava em **0% o run inteiro** na tela de execuções, enquanto a tela de
  Configurações, que lê outro contador, mostrava o valor certo;
- a análise de semelhantes nascia em **100%**;
- o inventário enchia a barra antes de a análise terminar;
- e o ETA do painel de duplicados dividia pelo próprio numerador, respondendo "menos de 1 min" com
  cem mil arquivos por processar.

Havia ainda **dois estimadores independentes**: uma média cumulativa em Java e uma janela deslizante
de ~25 s em JavaScript, sobre campos diferentes. A mesma execução lia diferente em duas telas, e
descobrir qual era qual exigia rastrear se o número vinha do Java ou do navegador. Somavam-se quatro
vocabulários de apresentação, um deles sem forma em horas — anunciava um backlog de cinco horas em
minutos.

## Decisão

**Cada workload declara a semântica do seu progresso, e há uma única aritmética.**

1. `ExecutionProgressModels` declara, por `ExecutionType`: qual contador é *done*, qual é a unidade
   (arquivos, itens, centésimos, etapas) e **se um ETA é honesto**. Um tipo ausente da tabela mantém
   o comportamento histórico — silêncio significa "não auditado", nunca "auditado e comum".
2. `ExecutionProgressReader` responde *done*, *total* e percentual a partir dessa declaração.
   `EtaEstimator` responde o tempo restante a partir dela. **Percentual e ETA passam a ser duas
   vistas da mesma medição**, e não duas opiniões.
3. A estimativa é **média recente em janela temporal**, medida por duas marcas na própria linha.
4. O front **não estima**: recebe `{state, remainingSeconds}` e apenas formata, num vocabulário
   único.

### Por que janela recente, e não as alternativas

Medido sobre um run real de 169 chunks de fingerprint, sem nada concorrendo:

| Estimador | Viés (horizonte de 60 chunks) |
| --- | --- |
| mediana dos 7 recentes | **−13,6%** |
| mediana dos 11 recentes | **−16,8%** |
| EMA α=0,25 | −12,3% |
| janela longa o bastante | −5,2% |
| janela de um ciclo de interferência | **+0,1%** |

A interferência dominante era o checkpoint periódico do próprio banco: ~65 s a cada ~5 min, **18% do
tempo de parede vindo de duas ocorrências**. Ela **recorre**, então não é anomalia a descartar — e é
exatamente isso que toda janela curta faz, prometendo um fim que não chega. A média cumulativa erra
do outro lado: nunca esquece, e uma interferência no início de um run de cinco horas ainda inflava a
estimativa horas depois.

### Por que duas marcas, e não uma âncora

Uma âncora só, rolada quando envelhece, **zera o span medido a cada rolagem** — a estimativa cairia
para "calculando" uma vez por janela, para sempre. Com uma marca velha e uma nova, a nova é promovida
quando envelhece e o span nunca cai abaixo de uma janela.

O estado mora na **linha**, não na memória do worker, por duas razões: a tela renderiza no processo
App enquanto o trabalho corre no Worker, e um reclaim precisa poder **descartar** a medição — uma
tentativa nova refaz o trabalho, e herdar a taxa anterior descreveria trabalho que está sendo
repetido.

### A janela é calibração, não regra do domínio

O default de 5 min é o valor **medido nesta máquina**, em `@ConfigurationProperties`. O que é
permanente é o critério: *a janela contém ao menos um ciclo completo da interferência recorrente do
ambiente*. Outra máquina, outro ciclo, outro valor.

### `total_expected` é o backlog daquela execução

Não é o total do catálogo, e **muda entre execuções**: observado caindo de 113.084 para 105.384 entre
dois runs do mesmo fingerprint, porque o backlog pendente diminuiu. Dentro de um run ele é estável,
já que cada run o semeia uma vez no início. Se algum dia um run passar a absorver itens novos no
meio, esse campo precisa ser reescrito junto — do contrário a barra e o ETA passam a dividir por uma
população que deixou de existir.

## Consequências

- Migration **V58** acrescenta quatro colunas; puramente aditiva, sem transporte de dado.
- **Zero escritas adicionais**: as marcas viajam no `UPDATE` de progresso que já acontecia.
- Um workload pode declarar que **não tem ETA honesto**, e isso é resposta, não lacuna: o dataset
  geográfico mede etapas de custos entre segundos e minutos, e a análise de semelhantes cresce com os
  pares comparados, não com os arquivos percorridos. Ambos respondem `NOT_APPLICABLE`.
- A precisão anunciada é limitada no backend, porque é afirmação sobre a medição e não sobre o
  idioma: o erro medido do melhor estimador foi de 20–25%, então horas arredondam para a hora.
- Um segundo estimador em JavaScript deixa de existir; a busca global que prova isso é teste.