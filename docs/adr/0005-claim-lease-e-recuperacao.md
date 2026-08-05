# ADR 0005 — Claim, lease, vivacidade e recuperação

## Status

Aceita.

## Contexto

Com App e Worker em processos separados ([ADR 0003](0003-app-e-worker-como-processos-separados.md)),
três perguntas deixaram de ter resposta local: quem está executando isto, ainda está vivo, e o que
fazer com o que foi abandonado.

A resposta original era memória. O `ExecutionCancellationService` mantinha um `Set<Long>` com as
execuções iniciadas *naquele* processo e um `isLive(id)` sobre ele; a recuperação de startup da App
consultava esse conjunto para não declarar interrompido algo que estava vivo. Um `Set` só responde
pelo processo que o mantém — e, quando o trabalho passou para o Worker, ele ficou permanentemente
vazio na App, respondendo sempre "não está vivo" sobre trabalho que estava.

Pior: App e Worker tinham **políticas diferentes de recuperação sobre as mesmas linhas**. A App
marcava tudo `INTERRUPTED`; o Worker devolvia à fila o que era retomável, fechava o que não era e
enfileirava um `RECONCILE` para a divergência que a interrupção pudesse ter deixado. Quem subisse
primeiro decidia — e uma App reiniciando antes do Worker fechava uma organização abandonada sem
nenhum reconcile, deixando disco e catálogo em desacordo sem reparo.

## Decisão

**`RUNNING` nasce exclusivamente do claim, e o claim grava a posse junto.** Um `UPDATE ... FOR UPDATE
SKIP LOCKED` escreve `status = 'RUNNING'`, `claimed_by`, `claimed_at` e `lease_until` na mesma
instrução. Nenhum outro caminho de produção atribui `RUNNING`.

**O lease é a autoridade de posse e de vivacidade, e vale entre processos.** Uma execução está viva
porque tem lease no futuro; está abandonada porque o dono parou de renovar. Qualquer processo lê a
mesma resposta, sem conhecer nada que o outro guarde.

**Memória da JVM não decide ciclo de vida.** O `isLive` e o `Set` que o sustentava foram removidos.
O que restou de memória no serviço de cancelamento é um cache de meio segundo sobre a resposta da
linha — otimização para um `if` que roda uma vez por arquivo, nunca a verdade. O `forget(id)` que
sobrou apenas despeja essa entrada quando a execução acaba.

**Renovação por heartbeat, e só o dono renova.** Um renovador dedicado estende o lease enquanto o
trabalho corre, e o `UPDATE` de renovação é condicionado ao dono — um segundo Worker não estende um
lease que não tem. `lease_until` significa posse, nunca prazo de processamento: um job renovado
corretamente dura horas.

**Uma política de recuperação, executada por qualquer processo ao subir.** `ExecutionReclaim` lê os
leases vencidos e decide por execução:

- **retomável** (`ExecutionJobHandler.resumable()`) → volta para a fila;
- **não retomável** → fechada como interrompida, e um `RECONCILE` é enfileirado para as pastas que
  ela tocava, porque o que move arquivos pode ter parado no meio;
- **tentativas esgotadas** → encerrada em erro, para sempre. Devolvê-la à fila seria devolvê-la
  invisível, já que o claim filtra pelo orçamento de tentativas.

`claim_count` é o único freio de poison job: incrementa quando a execução já tem seus locks e vai
começar, e **nunca decrementa**.

**Nada escapa da janela entre o claim e a contagem.** Uma falha nesse intervalo que **não** seja
contenção de caminho também consome a tentativa — não porque o trabalho tenha começado, mas porque a
alternativa não tem freio nenhum: a linha volta a `RUNNING` com o contador congelado em zero, o lease
vence, a recuperação a devolve, ela falha de novo, e o freio nunca engata porque lê justamente o
contador que não se moveu. Foi o que aconteceu com os dois backlogs de impressão digital durante toda
a fase 5. Contenção de caminho segue isenta, e é a única: ela descreve o momento, não o trabalho, e
cobrá-la gastaria o orçamento em espera.

## Consequências

- **Nenhuma política concorrente.** App e Worker chamam o mesmo `ExecutionReclaim` no seu próprio
  start. Rodar duas vezes é seguro por construção: a devolução à fila é um `UPDATE` condicionado à
  linha que foi lida, e o reconcile é deduplicado por pasta.
- **Um `PENDING` não é trabalho abandonado.** Nunca foi reivindicado, então não tem lease para
  vencer nem dono para perder: a recuperação não tem nada a dizer sobre ele, e o próximo Worker
  simplesmente o toma.
- **Um lease que expira sob carga não corrompe nada.** A execução vira trabalho abandonado e é
  tratada pela política; o que ela estivesse escrevendo é protegido pela verificação de posse dos
  locks, descrita no [ADR 0006](0006-concorrencia-de-mutacao-do-filesystem.md).
- **Cancelamento do usuário (`CANCELLED`) segue distinto de interrupção administrativa
  (`INTERRUPTED`)**, no estado persistido e no que a tela mostra.
- **A recuperação da App perdeu autonomia, e isso é o ponto.** Ela não decide mais o que fazer com
  trabalho que não é dela; aplica a mesma regra que o Worker aplicaria.

## Alternativas consideradas

- **Manter o `Set` e sincronizá-lo entre processos.** Seria construir um segundo canal de verdade ao
  lado de uma coluna que já respondia a mesma pergunta.
- **Perguntar ao sistema operacional se o processo dono ainda existe.** Não responde o caso que
  importa: um processo vivo que perdeu o banco não está executando nada de útil e precisa ser
  tratado como abandonado.
- **Recuperação só no Worker.** Se o Worker nunca subir, linhas abandonadas ficariam `RUNNING` para
  sempre e a tela de progresso ficaria eternamente esperando um trabalho que não acontece.
- **Recuperação só na App.** Era metade do problema original: a App não tem handler para julgar o
  que é retomável e fechava tudo do mesmo jeito.

## Como isto é verificado

- `ExecutionLivenessIntegrationTest`, com PostgreSQL real e sem transação compartilhada — dois
  commits são a visibilidade que um segundo processo tem. Prova que um lease segurado é visto como
  vivo por quem não sabe nada de quem o segura, que só o dono renova, que o vencimento torna a linha
  abandonada para todos, que um processo subindo não perturba o que outro faz, e que um `PENDING`
  não é trabalho abandonado.
- `ExecutionQueueIntegrationTest` cobre o claim concorrente com `SKIP LOCKED`, o índice único
  parcial da deduplicação e os `UPDATE` condicionais.
- O `Set` e o `isLive` não existem: varredura mecânica em zero.