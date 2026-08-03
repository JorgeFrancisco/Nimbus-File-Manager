# ADR 0001 — A verificação de atualização sai do computador

## Status

Aceita.

## Contexto

O Nimbus é distribuído como instalador. Uma vez instalado, ele não sabe que existe versão nova:
subir de versão dependia de alguém lembrar de olhar a página de releases. Software distribuído que
não sabe se atualizar envelhece na máquina de quem o instalou, e a versão instalada vira a versão
eterna.

O produto é **local-first**, e o README afirma que nada sai do computador. Isso era verdade sem
ressalvas: ffmpeg, o servidor PostgreSQL embarcado e a base geográfica são baixados, mas **porque
uma funcionalidade os exige** — o download é consequência de algo que o usuário pediu. Uma
verificação de atualização é diferente em espécie: ela acontece sozinha, periodicamente, sem que
ninguém tenha pedido nada.

O que uma requisição dessas revela ao servidor consultado: o endereço IP da instalação, o
user-agent, o horário, e — por estar buscando *a versão mais recente* — o fato de existir uma
instalação do Nimbus ali. A versão instalada **não** é enviada; a comparação acontece localmente.

## Decisão

A aplicação verifica periodicamente se há versão mais recente publicada, consultando a API de
releases do repositório, e a verificação pode ser desligada.

Consequências dessa escolha, todas deliberadas:

1. **Desligar é resposta de primeira classe.** `nimbus-file-manager.update.enabled=false` impede
   qualquer conexão — não é um contorno, é um modo de operação suportado.
2. **Nenhum identificador de instalação é enviado.** Sem id, sem contador, sem telemetria. A
   requisição é indistinguível de alguém abrindo a página de releases no navegador.
3. **A versão instalada não trafega.** O servidor responde qual é a última; quem compara é a
   máquina local.
4. **O endereço é configuração, não constante.** Uma instalação pode apontar para um espelho
   próprio — inclusive um servidor interno — sem depender de nova versão do aplicativo.
5. **Uma execução sem versão própria não consulta nada.** Rodando pela IDE ou pelo Maven não há
   manifest, logo não há o que comparar, e a requisição não é feita. A máquina de quem desenvolve
   nunca contata o endpoint.
6. **A configuração não vive no catálogo.** É property, não `AppSetting`: valor gravado no banco
   viaja dentro de um backup e reaparece descrevendo uma máquina onde nunca esteve — foi
   exatamente o defeito que tirou os caminhos das ferramentas externas do catálogo.

## Alternativas consideradas

**Não verificar nada.** Preserva a premissa sem ressalva alguma, e foi o estado até aqui. Rejeitada
porque transfere ao usuário a obrigação de lembrar de conferir uma página — e quem não lembra fica
para trás sem nunca saber disso, que é o pior dos dois mundos: nem privacidade escolhida, nem
atualização.

**Verificar apenas quando o usuário clicar.** Preserva a premissa quase inteira. Rejeitada como
comportamento *único* porque ninguém clica num botão para descobrir algo que não sabe que existe —
mas mantida como ação disponível na tela, ao lado da verificação automática.

**Servidor próprio de atualização.** Daria controle sobre o que é registrado, ao custo de operar
infraestrutura e de criar exatamente o canal de telemetria que esta decisão evita.

## Consequências

O README deixa de poder afirmar "nada sai do computador" sem qualificação: passa a dizer o que sai,
quando, e como desligar. Uma afirmação de privacidade que não descreve o comportamento real é pior
do que a conexão que ela esconde.