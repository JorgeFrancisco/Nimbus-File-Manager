# ADR 0003 — App e Worker como processos separados

## Status

Aceita.

## Contexto

Todo o processamento pesado do produto — inventariar uma biblioteca, organizar milhares de arquivos,
converter vídeo, calcular impressões digitais, agrupar semelhantes, reconstruir metadados, resolver
localizações, importar a base geográfica — rodava dentro da JVM que também servia as telas.

Isso custava três coisas ao mesmo tempo. A interface disputava CPU e memória com o trabalho de fundo,
num produto que roda na máquina pessoal de quem o usa. O ciclo de vida do trabalho vivia em campos de
memória (`AtomicBoolean running`, `AtomicLong processed`, `AtomicReference lastResult`), então uma
tela só conseguia responder "está rodando?" enquanto quem perguntava era o mesmo processo que
trabalhava — e nada sobrevivia a um restart. E o Spring Batch mantinha um segundo modelo persistente
de execução (`JobInstance`, `JobExecution`, `StepExecution`) descrevendo o que a tabela `execution`
já descrevia, com dois históricos concorrentes do mesmo trabalho.

## Decisão

**Dois processos, com papéis nomeados por Spring profile.**

- **App** (`app`) supervisiona e conversa: sobe o PostgreSQL embarcado, roda as migrations, serve a
  UI e a API, observa a pasta da biblioteca, mantém a bandeja, valida o que o usuário pediu, escreve
  o pedido na fila e lê estado durável para mostrar. Ela **inicia e supervisiona** o processo do
  Worker.
- **Worker** (`worker`) executa: reivindica trabalho da fila e o realiza. Nunca supervisiona o banco,
  nunca roda Flyway, nunca abre bandeja, nunca instala atualização.
- **`app-worker-combined`** ativa os dois papéis numa JVM só, para desenvolvimento.

**Quem pede não executa.** A App enfileira (`ExecutionEnqueueService.enqueueOrExisting`), o Worker
reivindica e executa (`ExecutionJobHandler`), a UI lê a linha. Um agendador também só pede: o timer
que mantém a base geográfica atualizada, a purga de catálogo e o watcher de pasta enfileiram e vão
embora. O que trafega entre os dois lados é uma linha de `Execution`, cujo protocolo está no
[ADR 0004](0004-execution-como-protocolo-duravel.md).

**Todo workload pesado novo pertence ao Worker por padrão.** Ficar na App é a exceção que precisa de
decisão explícita — as três que existem estão no [ADR 0008](0008-operacoes-assincronas-da-app.md).

## Consequências

- **Restart independente.** Fechar e reabrir a aplicação não mata trabalho em curso, e um Worker que
  cai é reiniciado pelo supervisor sem que ninguém perceba — até um limite. Depois de um número de
  falhas consecutivas de partida (`WorkerRestartPolicy`), a supervisão **desiste** e registra um
  ERROR: o que estiver errado a essa altura não se conserta com mais uma JVM. É o único estado em que
  a aplicação segue servindo telas sem nenhum Worker, e nele o sintoma visível é execução parada em
  `PENDING`.
- **Worker indisponível não faz a App executar localmente.** O pedido fica `PENDING`, que é
  exatamente o estado que deve sobreviver a qualquer reinício. Não há caminho de fallback que rode o
  trabalho "aqui mesmo por enquanto" — foi a existência desse caminho que criou os dois motores que
  esta decisão removeu.
- **Nada do que a tela mostra pode viver em memória.** O processo que desenha a barra não é o que
  faz o trabalho, o que forçou progresso e resultado a serem duráveis — ADR 0004 e
  [ADR 0007](0007-resultados-derivados-duraveis.md).
- **A separação tem preço.** Duas JVMs consomem mais memória base do que uma, e o orçamento de heap
  passou a ser configurado por papel. É o preço de a interface não competir com o trabalho.

## Alternativas consideradas

- **Thread pools separados na mesma JVM.** Era o desenho anterior, com três pools. Não resolve nada
  do que motivou a mudança: o ciclo de vida continua em memória, o restart continua perdendo tudo, e
  a interface continua dividindo a mesma JVM com o trabalho.
- **Um serviço do Windows, ou um segundo launcher no MSI.** Mais uma coisa para versionar, assinar e
  manter em sincronia. O contexto Spring da App já supervisiona um processo filho — o PostgreSQL
  embarcado —, então o padrão existia e estava endurecido.
- **Fila externa (broker).** Um produto que se instala com duplo-clique não pode exigir mais um
  serviço. O PostgreSQL já está lá.

## Como isto é verificado

- `HeavyWorkloadArchitectureTest` reprova o build se uma tela ou um agendador alcançar um serviço
  pesado, ou se aparecer um `@Async` fora dos três deliberados.
- `MutationBoundaryArchitectureTest` (P1–P5) reprova se um controller alcançar um port de mutação.
- Os papéis são profiles Spring, e há teste de composição que sobe um contexto por papel.