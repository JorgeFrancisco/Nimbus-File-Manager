# Evolução do Nimbus File Manager — arquitetura e produto

Documento **vivo** de sugestões, não de decisões. O que virar decisão desce para um ADR em
`docs/adr/`; o que virar estado atual pertence ao README.

**Item concluído é removido daqui**, e não marcado como feito: a lista existe para dizer o que falta,
e histórico é assunto do git. Os identificadores (`A6`, `P8`, …) **não são renumerados** quando algo
sai, para que uma conversa antiga que cite `P4` continue apontando para o mesmo item — e o que ele era
se acha em `git log -S"P4." -- docs/evolucao-arquitetura-e-produto.md`, que é onde vive o histórico.

Escrito a partir de uma varredura do código em 2026-07-31, com o produto na versão 5.26, e
reconciliado com o código a cada entrega que remove um item — a última em 2026-08-07, versão
8.1.1.198. O critério
que orienta todas as recomendações é a intenção declarada: **o produto está amadurecendo para ser
distribuído**. Isso muda o peso das coisas — o que hoje é "detalhe de ambiente do Jorge" vira
"primeira impressão de um estranho", e o que hoje é "eu sei que precisa reiniciar" vira suporte.

---

## Arquitetura

### Complexidade média — semanas

**A6. Não há API para automação.** `/api/**` é autenticado por sessão de formulário com CSRF, o que é
correto para a tela, mas fecha a porta para script, integração ou app móvel: não existe token.
*Entrega:* um caminho de extensibilidade. Também é pré-requisito para qualquer cliente que não seja o
navegador — inclusive um app de celular, se o produto for por aí.

**A11. A credencial do banco é protegida só pelo que o workspace herdou.** `cluster.properties`
guarda a senha do PostgreSQL embarcado em texto, e hoje quem a protege são as permissões que a pasta
do workspace herdou de onde ela nasceu. No perfil do usuário isso costuma bastar; um workspace
apontado para fora dele — outra unidade, uma pasta compartilhada — pode herdar ACLs bem mais largas,
e ninguém é avisado.
*Entrega:* endurecer isso é fatia própria, e a escolha entre owner-only, grupo local do Windows,
DPAPI ou equivalente **depende de uma decisão anterior**: se o workspace é sempre de uma conta ou se
pode ser compartilhado entre contas Windows. Decidir a proteção antes do modelo seria escolher a
fechadura sem saber quantas pessoas precisam da chave. Não bloqueia o handoff App → Worker, que já
está provado de ponta a ponta: o arquivo é lido pelos dois processos exatamente como está.

### Complexidade alta — meses

**A7. Uma instalação, uma biblioteca.** `WATCH_FOLDER` é um `AppSetting` global único. Trocar de
biblioteca já é uma operação suportada e segura — o pedido é validado, o que está rodando é
cancelado e o catálogo da coleção antiga é limpo —, mas continua sendo *troca*: as duas coleções
nunca coexistem, e todos os usuários veem a mesma. Para uso pessoal isso é suficiente. Para
distribuição, é a primeira pergunta de quem tem fotos em dois HDs — ou de uma família com duas
pessoas.
*Entrega:* multi-biblioteca destrava tanto o caso "meus discos" quanto o caso "minha família", e é
uma decisão estrutural: mexe em catálogo, inventário, quarentena e telas.

---

## Produto

### Complexidade alta — meses

**P8. O produto organiza, mas não conta histórias.** Hoje ele responde "o que eu tenho e onde está".
Não responde "o que vale a pena olhar". Álbuns automáticos por evento (agrupando por proximidade de
data e lugar — dados que o catálogo já tem), retrospectivas e detecção de fotos parecidas para
escolher a melhor são a camada que transforma arquivo em memória.
*Entrega:* é o que faz alguém abrir o programa sem ter uma tarefa a cumprir. Também é o que diferencia
de um explorador de arquivos com esteroides.

**P9. Nada sai do computador.** Local-first é uma virtude e deve continuar sendo o default. Mas há um
degrau entre "só local" e "nuvem": sincronizar o catálogo entre duas máquinas suas, ou acessar a
timeline pelo celular na rede de casa.
*Entrega:* multiplica o valor sem abrir mão da premissa. Exige decisão explícita sobre privacidade,
o que é material para ADR.

**P10. Reconhecimento de pessoas.** É o recurso que todo acervo pessoal acaba pedindo, e o único da
lista que envolve modelo de ML embarcado, armazenamento de dados biométricos e implicações legais
(LGPD/GDPR) — mesmo rodando 100% local.
*Entrega:* alto valor percebido, alto custo, e a decisão certa é provavelmente **não fazer agora** —
mas fazer conscientemente, não por omissão.

---

## Três caminhos possíveis

O produto está bom o suficiente para escolher uma direção; tentar as três ao mesmo tempo é o risco
real desta fase.

**Caminho 1 — Ferramenta de curadoria séria.** Público: quem tem 100 mil fotos e um problema de
organização. O diferencial é a integridade: mover arquivo com verificação
byte a byte, undo, trilha de auditoria. Ninguém no mercado de consumo faz isso.

**Caminho 2 — Substituto do Google Fotos local.** Público: família que quer sair da nuvem. Prioriza
P8, P9, A7. Exige a camada de encantamento e multiusuário de verdade. Concorrência forte
(Immich, PhotoPrism) e barra alta de UX.

**Caminho 3 — Motor de organização para profissionais.** Público: fotógrafo, arquivista, quem recebe
cartão de memória toda semana. Prioriza A6 (API) e regras de ingestão automáticas. Menor público,
maior disposição a pagar, e é onde a arquitetura atual já está mais pronta.

Minha leitura: o **Caminho 1** é o que exige menos desvio do que já existe. Os dois gargalos que eram
comuns aos três já saíram: instalar exigia criar role no PostgreSQL, e depois a versão instalada era
a versão eterna, o move sobre disco hostil passou a ser testado onde ele realmente falha, e o catálogo
ficou explorável pela Timeline. Não sobra gargalo comum aos três: daqui em diante a ordem depende
inteiramente do caminho escolhido.

---

## Sequência sugerida

Ordem por dependência e risco, não por valor isolado:

1. Escolher o caminho, e com ele entre **A7** (multi-biblioteca), **P8** (álbuns) e **A6/P9**.

## O que eu não recomendaria agora

Dizer não também é sugestão:

- **Reescrever o front-end em SPA.** Thymeleaf + JS incremental está sustentando 18 telas com custo
  baixo. Trocar por React resolveria problemas que o projeto ainda não tem e criaria os que ele não
  quer.
- **Microsserviços.** O monólito modular está bem separado, e o gargalo que se costumava invocar
  para justificá-los — processamento disputando a JVM das telas — já foi resolvido por fila durável e
  processo worker, sem rede entre serviços.
- **Trocar o PostgreSQL.** Ele sustenta o modelo com folga, e o motivo histórico para cogitar a
  troca — obrigar quem instala a instalar um servidor antes — deixou de existir: a aplicação
  baixa o servidor, cria o cluster e o desliga no fechamento, sem ninguém saber que ele está ali.
- **Reconhecimento facial (P10) como próximo passo.** É o único item que envolve dado biométrico e
  modelo de ML embarcado; fazê-lo antes de A7 ou P8 é escolher o mais caro primeiro.