# ADR 0007 — Resultados derivados duráveis, e como são publicados

## Status

Aceita.

## Contexto

Parte do que o produto mostra não é dado do usuário nem contador: é **resultado derivado** — algo
caro de calcular, que a tela lê e sobre o qual o usuário decide. Os grupos de fotos semelhantes, o
plano de uma organização antes de executá-la, a simulação de um rebuild de metadados.

Antes, esses resultados viviam na memória de quem os calculou: um cache de cinco entradas, um
`LinkedHashMap`, um `AtomicReference`. Isso trazia três problemas ao mesmo tempo. Sumiam no restart,
justamente quando tinham custado mais caro. Só existiam para o processo que calculou — e o cálculo
mudou de processo. E, pior, produzir um deles exigia que a App compusesse as classes capazes de mover
os arquivos do usuário, o que mantinha viva a última exceção da fronteira de mutação.

Havia ainda a pergunta que ninguém fazia: um resultado calculado com certos parâmetros, sobre uma
biblioteca que mudou desde então, **ainda responde à pergunta que foi feita?**

## Decisão

**Um resultado derivado que a tela lê é persistido, e sua casa é pendurada na execução que o
produziu.** A chave primária é o id da execução: um resultado por execução, e nenhum resultado sem
uma. Hoje são `similarity_grouping`, `organization_plan` (com seus itens) e
`metadata_rebuild_preview` (com os seus).

**Nada parcial é visível.** O cálculo escreve muitas linhas numa transação longa e um segundo passo
curto as torna a resposta. Duas formas, conforme o resultado seja lido enquanto a execução vive ou
só depois dela:

- **protocolo de publicação explícito** quando a tela lê o resultado *enquanto* outras execuções
  ocorrem: o registro nasce `BUILDING`, e um `UPDATE ... WHERE status = 'BUILDING'` condicional o
  promove a pronto. A leitura filtra pelo estado publicado, então não existe instante em que meia
  resposta apareça;
- **o próprio status da execução como bandeira** quando a leitura só pergunta pelo resultado de uma
  execução terminada. Uma tentativa que morreu no meio deixa linhas que ninguém pede, e a retentativa
  as substitui.

Publicar um resultado novo **supersede** o anterior em vez de apagá-lo: o antigo continua visível e
utilizável enquanto o novo é calculado, e depois de um cálculo que falhou ou foi cancelado. Como a
supersessão é a própria publicação, um cálculo que não chega até ela não custa nada ao usuário — é o
que torna o cancelamento seguro onde ele existe.

**Os parâmetros fazem parte da identidade do resultado.** Um agrupamento pedido a 85% não pode ser
respondido com um calculado a 90%. O que o resultado responde é gravado com ele, de forma injetiva —
serialização canônica com prefixo de comprimento, para que duas listas de parâmetros diferentes não
possam produzir a mesma assinatura.

**Um resultado desatualizado é entregue com a ressalva, não escondido.** Junto do resultado grava-se
uma assinatura do que ele examinou; quando a biblioteca mudou desde então, a leitura devolve o
resultado e diz que está desatualizado. Sonegá-lo deixaria o usuário sem nada enquanto uma resposta
perfeitamente utilizável está no banco.

**Quando não há resultado publicado para os parâmetros pedidos, a API responde `202`** com a execução
que vai produzi-lo, em vez de calcular dentro da requisição.

## Consequências

- **O resultado sobrevive ao processo, ao restart e à troca de quem calcula.** É o que permitiu ao
  cálculo mudar de JVM sem a tela mudar de comportamento.
- **A App deixou de compor o que move arquivos** para produzir um preview, o que fechou a última
  exceção da fronteira de mutação ([ADR 0006](0006-concorrencia-de-mutacao-do-filesystem.md)).
- **Custo em disco e em migrations.** Cada resultado derivado novo é uma tabela e uma migration, com
  a política de retenção que lhe couber — o plano de organização, por exemplo, expira sozinho, muito
  antes do histórico da execução que o gerou.
- **A tela precisa saber dizer "isto está desatualizado"**, o que é mais trabalho de interface do que
  simplesmente não mostrar nada.
- **Nem todo resultado merece tabela.** Contadores e uma frase cabem na própria linha da execução
  ([ADR 0004](0004-execution-como-protocolo-duravel.md)); tabela é para o que se olha e sobre o que
  se decide.

## Alternativas consideradas

- **Recalcular a cada leitura.** É o que a tela de duplicados fazia, e o motivo de ela demorar: o
  agrupamento é quadrático no número de candidatos.
- **Cache em memória com TTL.** Some no restart, não atravessa processos, e não responde se o
  resultado ainda vale para a biblioteca de agora.
- **Publicar apagando o anterior.** Deixaria o usuário sem resposta durante o recálculo, e sem
  nenhuma se ele falhasse.
- **Uma coluna JSON genérica na `execution`.** Tentador e errado: transformaria a linha da fila em
  depósito de qualquer coisa, sem esquema, sem consulta e sem paginação.

## Como isto é verificado

- Testes de integração com PostgreSQL real cobrem a publicação condicional, a supersessão do
  resultado anterior e a invisibilidade do parcial.
- A injetividade da assinatura de parâmetros tem teste próprio, com listas construídas para colidir
  sob concatenação ingênua.
- Os testes de leitura cobrem os estados que a tela distingue: não analisado, publicado,
  desatualizado e cobertura parcial.