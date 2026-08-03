# ADR 0002 — Análise de bytecode com SpotBugs e find-sec-bugs

## Status

Aceita.

## Contexto

A régua de qualidade estática do projeto era uma só: o Sonar, rodado ao final de cada tarefa, sem
issue nova. Ela cobre bem os padrões clássicos de bug e as regras de estilo, porque analisa o
**código-fonte**.

O que ela não cobre é a superfície de segurança desta aplicação em particular. O Nimbus recebe
caminhos digitados pelo usuário, executa processos externos (ffmpeg, exiftool, pg_dump, msiexec),
descompacta arquivos baixados da internet, verifica hashes e escreve scripts que o `cmd` interpreta.
Path traversal, injeção de comando, *zip slip* e criptografia fraca são exatamente as categorias que
importam aqui — e são as que o Sonar Community reporta de forma limitada, por não fazer análise de
*taint*.

Foi considerado também o OWASP Dependency-Check, e **descartado**: exige chave da API do NVD, cache
de gigabytes e uma cadência de calendário própria, o que conflita com a regra *Clone limpo executa*
e agrega burocracia desproporcional ao ganho para este projeto.

## Decisão

Adotar **SpotBugs** com o plugin **find-sec-bugs**, num profile Maven próprio (`-Pspotbugs`), com o
goal `check` reprovando o build no primeiro achado.

O profile existe pelo mesmo motivo do `pitest`: o build do dia a dia é o executado dezenas de vezes
por dia e não deve engordar. O SpotBugs sozinho seria quase redundante com o Sonar — quem justifica
a adoção é o find-sec-bugs.

Achados se resolvem corrigindo o código ou excluindo com justificativa em `spotbugs-exclude.xml`,
nunca com `@SuppressFBWarnings` espalhado pelas classes: a decisão fica no único arquivo onde todas
são revisadas juntas. Exclusão global vale só para o que o detector reporta e é **regra deste
projeto**; caso analisado individualmente fica preso à sua classe.

## O que a primeira execução mediu

Com `effort=Max` e `threshold=Low`, sobre Java 25 (que o SpotBugs 4.10.3.0 digeriu sem ajuste):
**823 achados brutos**.

Deles, **666 eram estruturais** — e o maior grupo não era ruído de biblioteca, como se supôs no
início: 272 dos 345 `EI_EXPOSE_REP2` estão em serviços e adapters porque o detector denuncia
justamente a **injeção por construtor com campos `final`**, que é regra deste documento. Somam-se a
marcação informativa de handlers (`SPRING_ENDPOINT`, 131) e o log de caminhos de arquivo
(`CRLF_INJECTION_LOGS`, 190).

Dos **20 achados de segurança**, todos foram lidos um a um e **nenhum era defeito**: os
`COMMAND_INJECTION` são `ProcessBuilder` sobre `List<String>`, que não passa por shell; o *zip slip*
no `ExternalToolInstaller` é impossível porque a entrada é reduzida ao último segmento antes de ser
resolvida; o `XSS_SERVLET` escreve apenas texto do bundle; e os quatro `UNVALIDATED_REDIRECT` têm
destino constante.

Os **28 restantes eram dívida real e foram corrigidos**: nove `toLowerCase`/`toUpperCase` sem
`Locale`, sete campos públicos mutáveis em `MetadataRebuildCounters`, cinco chamadas a método
sobrescrevível no construtor dos schedulers, dois `Reader` fora do try-with-resources, dois
*unboxing* redundantes e — o único de concorrência — um `ConcurrentHashMap` no `OperationLockService`
usado como monitor de uma seção crítica composta, onde a coleção concorrente não garantia nada e
sugeria uma segurança que vinha do `synchronized` o tempo todo.

## Consequências

- Toda tarefa passa a ter duas réguas: Sonar sem issue nova e `-Pspotbugs verify` verde.
- O `spotbugs-exclude.xml` vira documento de decisões, não configuração: entrada sem justificativa
  conferida contra o código é débito.
- O custo recorrente é baixo — cerca de um minuto por execução —, mas o custo de entrada foi a
  triagem das 823, que só se paga uma vez.
- A comparação de hash do instalador (`Checksums.matches`) deixou de depender do locale da máquina,
  efeito colateral direto de levar um alerta a sério em vez de silenciá-lo.