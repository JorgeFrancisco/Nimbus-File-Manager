# ADR 0009 — Um arquivo de log por processo, e o papel na linha

## Status

Aceita.

## Contexto

App e Worker são dois processos ([ADR 0003](0003-app-e-worker-como-processos-separados.md)) e
escreviam no **mesmo arquivo**: `logback-spring.xml` nomeava `nimbus-file-manager.log` sem PID nem
papel, e o Worker resolve o mesmo workspace que a App — ele é iniciado sem override de workspace ou
de logging, herdando o mesmo `user.home`.

Duas JVMs sobre um `RollingFileAppender` não é uma configuração suportada:

- só é seguro entre processos em **prudent mode**, que a configuração não usava;
- prudent mode **não rola por tamanho**, e a política aqui tem `maxFileSize` — então nem ligá-lo
  resolveria sem trocar a política de rotação;
- os dois processos **renomeiam o arquivo por baixo um do outro** ao rolar, e quem perde a corrida
  segue escrevendo num inode renomeado ou recria o arquivo;
- `cleanHistoryOnStart` roda nas duas JVMs, e o Worker sobe depois da App;
- sem o lock por escrita do prudent mode, linhas dos dois processos podem se intercalar.

Agrava que `ProcessBuilderWorkerLauncher` usa `inheritIO()`: no produto empacotado normalmente não há
console, então **o arquivo é o único registro do Worker** — justamente o que estava em risco.

Há ainda o caso de desenvolvimento. O profile `app-worker-combined` é **uma** JVM com os dois papéis,
escrevendo num console só, e ali nenhum nome de arquivo distingue quem falou.

## Decisão

**Cada processo escreve o seu arquivo, e o papel está no nome.** `nimbus-file-manager-app.log`,
`nimbus-file-manager-worker.log`, `nimbus-file-manager-combined.log`. O papel sai dos profiles ativos
por `<springProfile>`, com três expressões mutuamente exclusivas — o grupo ativa `app` e `worker` ao
lado de si mesmo, então `worker` sozinho também casaria com uma execução combinada. O padrão de
arquivamento carrega o mesmo nome, ou os dois processos voltariam a colidir na primeira rotação.

**O console traz o papel da linha**, com largura fixa: `[APP]`, `[WORKER]`, `[COMBINED]`. O valor vem
do MDC, e o *fallback* é o papel do próprio processo — de modo que uma App ou um Worker instalados
estão certos sem que nada precise ser marcado em tempo de execução, e só a execução combinada depende
do marcador.

**No arquivo, o papel aparece só onde o nome não o responde.** Num processo de um papel só, toda linha
do arquivo tem o mesmo papel e o nome já disse qual — repeti-lo em cada linha seria uma coluna
constante de ruído. O combinado é a exceção que justifica o marcador: um arquivo, os dois papéis
dentro, e um nome que diz "combined" não distingue linha nenhuma.

**No combinado, o fallback é `COMBINED`, não `APP`.** O token nomeia o **runtime**, não a natureza do
componente. Uma linha que o Worker produziu onde o marcador não alcançou leria como sendo da
aplicação, o que é pior do que admitir que o papel daquela linha não foi provado. `SHARED` responderia
outra pergunta — se um componente é infraestrutura compartilhada é propriedade do código, não do
processo que emitiu a linha.

**O marcador é colocado onde uma thread nasce para um papel e vive nele inteiro**: os loops de claim e
o renovador de lease. E é carregado através do único *fan-out* que o perderia, o pool do
`ProcessingCoordinator`, com limpeza em `finally` para que uma thread reutilizada não leve o papel de
uma execução para a seguinte. Nada além disso: seguir todo callback seria uma infraestrutura grande
para um prefixo.

## Consequências

- **A rotação deixou de ser uma corrida entre processos**, e o log do Worker deixou de poder ser
  truncado pela App.
- **Diagnosticar passa a envolver dois arquivos** quando não se sabe de qual papel veio o sintoma. É o
  preço, e é menor que o de um arquivo que se perde ao rolar.
- **Quem coletava `nimbus-file-manager.log` por nome não encontra mais o arquivo.** O nome mudou; não
  existe alias.
- **O papel no console é do runtime que emitiu a linha**, não do componente. `ExecutionEnqueueService`
  aparece como `WORKER` quando o Worker o chama e como `APP`/`COMBINED` quando a App o chama.

## Alternativas consideradas

- **Ligar `prudent mode` e manter um arquivo.** Incompatível com rotação por tamanho, e ainda assim a
  documentação do Logback não garante rotação entre JVMs.
- **Derivar o papel do pacote da classe.** Mente neste código: `ExecutionReclaim` está em
  `worker.application` e é chamado pela App no startup; os handlers não moram sob `worker.*`, mas em
  cada domínio; `ExecutionEnqueueService` é chamado pelos dois.
- **Marcador só por profile, sem MDC.** Correto nos processos instalados e mudo justamente no
  combinado, que é onde o console mistura os dois.
- **MDC propagado em toda parte.** Muita máquina para um prefixo, e cada ponto esquecido vira um
  rótulo errado em vez de ausente.

## Como isto é verificado

`logback-test.xml` tem precedência sobre `logback-spring.xml` na suíte — de propósito, para os testes
não escreverem no log da instalação da mesma máquina —, então **a configuração de produção não é
exercitada rodando nada**. O que se pode verificar é lida do arquivo, e é o que `LogbackConfigurationTest`
faz: um único appender de console e nenhuma inclusão do appender do Boot (duas referências imprimiriam
tudo duas vezes), o nome do arquivo e o padrão de arquivamento carregando o papel, os três perfis
resolvendo as duas propriedades, e a chave do MDC no pattern casando com a que o código escreve.

`LoggingRoleTest` cobre o resto: uma thread do Worker se diz Worker, uma thread não marcada não diz
nada, o papel atravessa para a thread do pool, e — os dois que importam — não sobra na thread
reutilizada, nem quando a tarefa lança.