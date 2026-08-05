# ADR 0004 — `Execution` como protocolo durável de comando e execução

## Status

Aceita.

## Contexto

Com o trabalho acontecendo em outro processo ([ADR 0003](0003-app-e-worker-como-processos-separados.md)),
um pedido deixou de poder ser uma chamada de método. Ele precisa atravessar uma fronteira de
processo, sobreviver ao reinício de qualquer um dos lados, e continuar respondendo "o que aconteceu
com aquilo que eu pedi?" para quem não estava lá quando aconteceu.

Havia, além disso, um segundo modelo persistente descrevendo a mesma coisa: o Spring Batch mantinha
`JobInstance`, `JobExecution` e `StepExecution` ao lado da tabela `execution`, com dois históricos
concorrentes do mesmo trabalho e nenhuma resposta única para "isto rodou?".

## Decisão

**Uma linha de `execution` é ao mesmo tempo o comando e o histórico dele.** O pedido, o que foi feito
e o que resultou são colunas da mesma linha. Não existe segundo modelo persistente de job — o Spring
Batch saiu inteiro, dependência e tabelas.

**O que a linha carrega:**

- **tipo** (`execution_type`), que é como o dispatcher escolhe o handler e como a concorrência é
  contada;
- **payload versionado** (`request_payload`), com o que o pedido tem de específico e um
  `schemaVersion` explícito;
- **caminhos** (`source_path`, `target_path`), que são o recurso sobre o qual a execução se exclui
  ([ADR 0006](0006-concorrencia-de-mutacao-do-filesystem.md));
- **chave de deduplicação** (`dedup_key`);
- **estado do ciclo de vida** — status, posse, lease, tentativas ([ADR 0005](0005-claim-lease-e-recuperacao.md));
- **progresso e resultado** — contadores, fase, percentual do item corrente e uma mensagem.

**Um payload de esquema desconhecido é recusado, não lido pela metade.** O handler compara o
`schemaVersion` e falha alto se não for o seu. Ler pela metade um pedido de outra versão é como
descartar todas as impressões digitais do catálogo por causa de um booleano interpretado errado — que
é literalmente o caso que a regra existe para evitar. A versão só muda quando o significado do
payload muda; acrescentar um campo opcional não é mudança de esquema.

**Pedir duas vezes a mesma coisa devolve a linha que já existe.** São **dois** índices únicos
parciais sobre `(execution_type, dedup_key)` — um restrito a `PENDING`, outro a `RUNNING` — e o banco
recusa o duplicado; o serviço de enfileiramento devolve a execução que já está a caminho. Dois, e não
um sobre os dois estados, porque um único índice proibiria exatamente o caso legítimo: **um pedido
esperando enquanto um idêntico roda**. O que entra na chave é o que distingue dois pedidos de
verdade: "refazer tudo" e "terminar o que falta" são dois pedidos; dois "terminar o que falta" são um
só.

**O que espera não é reivindicado enquanto o idêntico roda.** É o outro lado da regra 1 + 1, e é
condição do próprio claim, não só do índice: tomar a linha que espera escreveria `RUNNING` uma
segunda vez para a mesma chave, que é o que o índice recusa. Deixada só para o índice, a recusa
chega como violação de integridade levantada de dentro do claim — e o loop a repete a cada rodada
enquanto a linha em execução continuar em execução. É o único ponto em que a fila filtra por algo
além de estado, disponibilidade e orçamento, e entra porque é uma coisa que a consulta consegue ver.

**A mensagem viaja como código e argumentos, nunca como texto.** Quem escreve é um processo sem
requisição atrás de si e, portanto, sem idioma; quem lê resolve no idioma de quem está olhando. Vale
para qualquer valor que a tela vá redigir — um enum guardado como enum, nunca como a frase que ele
vira.

**Progresso é da linha.** Contadores por lote, percentual do item corrente com escrita estrangulada,
e fase. Um `PENDING` sobrevive a qualquer reinício: é justamente o estado que precisa sobreviver.

## Consequências

- **A tela pergunta ao banco, não a um objeto.** Duas abas abertas mostram a mesma coisa, e a barra
  continua de onde estava depois de reiniciar qualquer um dos lados.
- **Quem pede recebe algo acionável.** Os endpoints que enfileiram respondem `202` com a execução a
  acompanhar, em vez de segurar a requisição pelo tempo do trabalho.
- **Um contador que ninguém mostra não vira coluna.** O que a tela não exibe fica na linha de log que
  a execução já escreve; o que ela exibe está na linha ou na mensagem.
- **Resultado que não cabe em contadores tem casa própria**, pendurada na execução —
  [ADR 0007](0007-resultados-derivados-duraveis.md).
- **Mudar o significado de um payload exige subir a versão**, e execuções antigas na fila falham alto
  em vez de rodar errado.

## Alternativas consideradas

- **Manter o Spring Batch.** A única capacidade que pagaria o segundo modelo seria retomar de um
  checkpoint, e ela nunca esteve em uso: o reader abria um `ExecutionContext` e não persistia cursor
  nenhum.
- **Payload sem versão.** Barato até o primeiro deploy em que o significado de um campo muda com uma
  linha antiga ainda na fila.
- **Guardar a mensagem já traduzida.** Seria gravar o idioma de quem executou; um relatório feito em
  pt-BR seria lido em pt-BR por quem está com a interface em inglês.
- **Uma tabela de fila separada da tabela de histórico.** Dois lugares para a mesma verdade, com a
  cópia entre eles como novo ponto de falha.

## Como isto é verificado

- `ExecutionQueueIntegrationTest`, contra PostgreSQL real: o índice único parcial recusando o
  duplicado, os `UPDATE` condicionais e o claim concorrente.
- Cada handler tem teste de que um payload de outro esquema é recusado antes de qualquer efeito.
- A paridade das chaves de mensagem entre os bundles é travada por teste dedicado, que quebra o build
  se um idioma ficar para trás.
- A migration `V17__spring_batch_tables_leave_the_catalog.sql` derruba as tabelas `BATCH_*`.