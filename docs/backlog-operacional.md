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