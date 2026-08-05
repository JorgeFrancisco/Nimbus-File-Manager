# ADR 0006 — Concorrência de mutação: exclusão por caminho, não por parada geral

## Status

Aceita.

## Contexto

Os arquivos do usuário são insubstituíveis, e mais de um caminho do produto os move: organizar,
desfazer, converter, mandar para a quarentena e restaurar de lá, renomear e excluir pela tela de
Arquivos. Com dois processos ([ADR 0003](0003-app-e-worker-como-processos-separados.md)), garantir
que dois deles não escrevam a mesma árvore ao mesmo tempo deixou de ser possível dentro de uma JVM.

Havia também uma janela de manutenção global: para trocar a biblioteca monitorada, tudo parava. Era
conservador demais por construção — parava trabalho que não tinha nada a ver com a troca — e frágil,
porque era um sinalizador que um processo levantava e o outro tinha de respeitar.

## Decisão

**Existe uma fronteira de mutação, e ela é uma capacidade que se detém.** Mudar arquivos do usuário
ou o catálogo da coleção passa por ports (`LibraryFileMutations`, `CatalogMutations`). Quem os detém
é trabalho do Worker alcançável a partir de um handler — nada mais. Não há lista de exceções: a
última saiu quando a troca de biblioteca virou execução, e o mecanismo de exceção saiu com ela.

**A exclusão é por recurso, e o recurso é um caminho.** Toda execução declara os caminhos em que
trabalha (`source_path`, `target_path`), e o dispatcher toma advisory locks do PostgreSQL sobre a
cadeia de prefixos canônicos desses caminhos antes de executar. Ancestrais são tomados em modo
compartilhado, de forma que:

- uma operação exclusiva sobre uma pasta impede operações nela e em seus descendentes;
- árvores irmãs independentes **não** se bloqueiam só por compartilharem um ancestral.

**Nem todo trabalho é uma pasta, e quem diz qual é qual é o handler.** Drenar um backlog, agrupar o
que já está com impressão digital, apagar linhas de catálogo vencidas — o recurso desses é uma
consulta, não uma árvore, e obrigá-los a inventar um caminho os faria esperar por (e bloquear)
trabalho com o qual não têm nada a ver. Então `ExecutionJobHandler.requiresPathLock()` declara isso,
com três propriedades que são o desenho e não detalhes:

- **o padrão é sim**, porque esquecer é o que se faz por acidente e a resposta que não corrompe nada
  é a que se recusa a começar;
- **quem alcança `LibraryFileMutations` não pode declarar não** — verificado, não confiado;
- **a decisão vem do tipo, nunca da linha.** "Sem caminho preenchido, logo sem lock" deixaria uma
  execução mutadora sair da exclusão por um campo esquecido; um tipo que exige caminho e não nomeia
  nenhum falha alto em vez de rodar.

**Quem chega segundo espera, não falha.** Um caminho ocupado devolve a execução à fila com o
orçamento de tentativas intacto; ela roda quando o primeiro soltar. Espera durável, não *retry* em
memória.

**A posse é reconfirmada antes de escrever.** Um servidor reiniciado derruba advisory locks sem
avisar ninguém, então nada irreversível acontece com a posse sabidamente perdida.

**Não existe janela de manutenção global.** O que exclui é o lock de caminho que toda execução já
toma. Foi assim que a troca de biblioteca deixou de parar o mundo — e é assim que o rebuild de
localização e a atualização da base geográfica se excluem: os dois declaram a pasta `geodata`, sem
que nenhum dos dois precise saber que o outro existe.

## Consequências

- **Paralelismo preservado.** Um inventário na biblioteca, um drain de impressões digitais e uma
  atualização da base geográfica correm juntos, porque trabalham em caminhos diferentes. Só o que
  colide espera.
- **Uma exclusão nova é uma linha de dados, não um mecanismo.** Dois workloads passam a se excluir
  declarando o mesmo caminho. Nenhum sinalizador, nenhum registro em memória, nenhuma coordenação
  entre processos a escrever.
- **O custo é a exatidão do mapeamento caminho→chave.** É a peça mais delicada do desenho: uma
  profundidade de prefixos errada faz a exclusão falhar em silêncio.
- **A janela entre mover o arquivo e escrever no banco não desaparece** — ela é detectável e
  reconciliável, e é para ela que existe o `RECONCILE` que a recuperação enfileira
  ([ADR 0005](0005-claim-lease-e-recuperacao.md)).

## Alternativas consideradas

- **Um lock global de mutação.** Simples de acertar e conservador demais: serializaria trabalho que
  nunca se toca, num produto cujo caso normal é justamente ter várias coisas acontecendo em pastas
  diferentes.
- **Manter a janela de manutenção para operações administrativas.** Era o desenho anterior. Um
  sinalizador que um processo levanta e outro respeita é exatamente a coordenação em memória que a
  separação de processos torna insustentável.
- **Locks em tabela própria, com linhas.** Advisory locks morrem com a sessão, que é a propriedade
  que importa: um processo que some solta o que segurava sem depender de ninguém limpar.
- **Confiar no lock do sistema de arquivos.** Não existe de forma portável, e não diz nada sobre a
  pasta — só sobre o arquivo aberto.

## Como isto é verificado

- `MutationBoundaryArchitectureTest` (P1–P5): quem pode deter um port de mutação, quem pode alcançar
  quem o detém, quais classes podem chamar `Files` de forma que altere algo, e a ausência de
  qualquer exceção temporária. Mais a regra que fecha a saída do parágrafo acima: nenhum handler que
  alcança `LibraryFileMutations` responde `requiresPathLock() = false`. A regra **pergunta ao
  handler** em vez de ler a declaração da classe — inferir pela declaração dava a resposta errada
  para quem herda a política de uma superclasse abstrata, que é a forma de dois handlers atuais.
- `OperationLockServiceIntegrationTest` e `ConcurrentActiveExecutionsIntegrationTest` exercitam os
  advisory locks com conexões distintas, incluindo caminhos aninhados.
- `OwnershipLossIntegrationTest` cobre a reconfirmação de posse.
- Todo movimento de arquivo do usuário passa por `SecureFileMove` (baseline SHA-256, verificação
  byte a byte, rollback), regra permanente do `AGENTS.md`.