# ADR 0008 — As três operações assíncronas que permanecem na App

## Status

Aceita.

## Contexto

A regra estabelecida é que processamento pesado pertence ao Worker
([ADR 0003](0003-app-e-worker-como-processos-separados.md)). Quatorze runners `@Async` existiam; onze
viraram execuções da fila. Três não viraram — e não por esquecimento.

O risco de não registrar isso é conhecido: "zerar a contagem de `@Async`" é uma métrica fácil de
perseguir e péssima de obedecer. Migrar estes três produziria um Worker que precisa matar o próprio
supervisor, um Worker que depende de si mesmo para existir, e um Worker cujas conexões são derrubadas
pelo trabalho que ele estaria executando.

## Decisão

**Exatamente três operações assíncronas permanecem na App**, e cada uma por um motivo estrutural:

| Runner | Por que não pode ser do Worker |
| --- | --- |
| `UpdateInstallAsyncRunner` | Baixa o instalador e **encerra a própria aplicação** para que ele rode. Um Worker subordinado teria de matar seu supervisor e sobreviver ao jar que está sendo substituído — ou seja, deixar de ser subordinado. |
| `ExternalToolInstallAsyncRunner` | Instala o ffmpeg que o Worker usa. Se o Worker dependesse dele para si mesmo, o primeiro uso seria uma dependência circular: sem ffmpeg não há conversão, e sem conversão ninguém instala o ffmpeg. |
| `CatalogBackupAsyncRunner` | Roda `pg_dump`/`pg_restore` contra o cluster que a App supervisiona, e o restore **derruba todas as conexões** — inclusive as do Worker. Quem coordena isso tem de ser o dono do cluster; o Worker é pausado durante a restauração. |

Sobre eles vale explicitamente que:

- **não são workloads de processamento da biblioteca**. Nenhum lê, hasheia, move ou converte mídia do
  usuário. São operações sobre a instalação e sobre o banco;
- **não constituem segundo motor de execução**. Não têm fila, não têm claim, não têm lease, e não
  aparecem no histórico de execuções — são ações do processo App, com progresso que a própria tela da
  App lê enquanto ela vive;
- **unicidade em memória é aceitável aqui**. Um `AtomicBoolean` responde pelo processo que o mantém,
  e nestes três o processo que mantém é o único que pode executar. Foi essa coincidência que deixou
  de valer para tudo o mais quando o trabalho mudou de JVM;
- **um quarto `@Async` na App exige decisão arquitetural nova**, registrada aqui ou num ADR próprio.
  O default é o Worker.

Também por decisão, **o estado do Worker não vai para a bandeja**: um Worker que cai é reiniciado
sozinho, então "parado" seria um estado quase sempre invisível; e quando o processamento realmente
não anda, o sintoma que o usuário percebe é execução parada em `PENDING`, que a tela de Execuções já
mostra melhor. Isto é decisão, não pendência.

A decisão continua valendo, com um caso conhecido em que ela é mais estreita do que parece: a
supervisão **desiste** depois de um número de falhas consecutivas de partida
([ADR 0003](0003-app-e-worker-como-processos-separados.md)), e nesse estado o Worker não volta
sozinho. Hoje o que informa isso é um ERROR no log mais as execuções paradas em `PENDING`; se essa
ausência precisar ser visível na interface, é assunto da tela de Execuções ou da saúde do Worker, não
da bandeja.

## Consequências

- **A fronteira é verificável em vez de lembrada.** Um `@Async` novo na App quebra o build, e quem o
  adicionou precisa ou movê-lo para o Worker ou registrar por que ele é o quarto caso.
- **O pool de threads assíncronas da App encolheu para um.** Os pools de análise visual e de
  geolocalização saíram junto com os workloads que os alimentavam — um pool sem quem submeta é
  orçamento de threads gasto em nada.
- **Um destes três parando não para o processamento.** Eles não estão no caminho do trabalho da
  biblioteca; no máximo adiam uma atualização, uma instalação de ferramenta ou um backup.

## Alternativas consideradas

- **Migrar os três para o Worker por uniformidade.** Descartada pelos três motivos da tabela: a
  uniformidade seria de contagem, não de arquitetura.
- **Migrar só o backup**, por ser o mais parecido com um job. Também descartada: é justamente o que
  derruba as conexões do Worker, e ele precisa ser pausado durante a restauração — quem coordena não
  pode ser quem é pausado. Reavaliável se um dia a restauração deixar de derrubar conexões.
- **Não registrar nada e confiar na revisão.** É o que já falhou antes: um runner órfão
  (`InventoryScanAsyncRunner`) sobreviveu a uma fase inteira sem chamador porque a varredura era
  manual.

## Como isto é verificado

`HeavyWorkloadArchitectureTest` reprova o build quando:

- existe um método `@Async` fora destas três classes;
- uma tela (`infrastructure.web` ou `infrastructure.rest`) alcança um serviço pesado;
- um agendador alcança um serviço pesado em vez de enfileirar.