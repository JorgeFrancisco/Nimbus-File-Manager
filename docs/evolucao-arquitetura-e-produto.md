# Evolução do Nimbus File Manager — arquitetura e produto

Documento **vivo** de sugestões, não de decisões. O que virar decisão desce para um ADR em
`docs/adr/`; o que virar estado atual pertence ao README.

**Item concluído é removido daqui**, e não marcado como feito: a lista existe para dizer o que falta,
e histórico é assunto do git. Os identificadores (`A1`, `P2`, …) **não são renumerados** quando algo
sai, para que uma conversa antiga que cite `P4` continue apontando para o mesmo item.

Escrito a partir de uma varredura do código em 2026-07-31, com o produto na versão 5.26. O critério
que orienta todas as recomendações é a intenção declarada: **o produto está amadurecendo para ser
distribuído**. Isso muda o peso das coisas — o que hoje é "detalhe de ambiente do Jorge" vira
"primeira impressão de um estranho", e o que hoje é "eu sei que precisa reiniciar" vira suporte.

## Retrato de hoje

| Dimensão | Número |
| --- | --- |
| Domínios | 22, hexagonais (`domain` / `application` / `infrastructure`) |
| Classes de produção | ~750, em ~48 mil linhas |
| Maiores domínios | `shared` (100 classes), `duplicate` (99), `metadata` (92), `organization` (70) |
| Testes | 2228, sendo **16 de integração** |
| Cobertura | 98,4% instrução · 91,7% branch |
| Telas | 18 |
| Idiomas | 2 (pt-BR, en) |
| Migrations | 13 |
| Empacotamento | Dockerfile + docker-compose |

### O que já está sólido

Vale registrar, porque delimita o que **não** precisa de atenção: a separação por domínio é
consistente e a direção das dependências é respeitada; a catraca de cobertura funciona; o Sonar está
zerado; há reconciliação self-healing, quarentena com restauração, undo de organização e trilha de
execuções. O `SecureFileMove` (hash + verificação + rollback) e o `SelfWrittenPathRegistry` são peças
maduras, reaproveitadas por organização, dedup, conversão e explorer. Isso é mais infraestrutura de
segurança de dados do que a maioria dos gerenciadores de arquivos tem.

---

## Arquitetura

### Complexidade baixa — dias

**A1. Nenhum teste exercita a interface.** Em uma única sessão de trabalho, seis defeitos de tela
passaram por build verde, cobertura no piso e Sonar limpo: `?w=null` nas miniaturas, expressão SpEL
inválida no combo, menu que não fechava por CSS, "Abrir" navegando para a pasta, voltar quebrado e
pasta não removida. Todos visíveis em dois minutos de uso, invisíveis para 2228 testes.
*Entrega:* um punhado de testes de fumaça que renderizam cada tela autenticada e falham em qualquer
exceção de template — o `SettingsPageRenderTest` criado ontem já é o molde. Cobre o modo de falha
mais frequente (expressão que não avalia) por um custo baixíssimo.

**A2. `docs/adr/` está vazio.** O AGENTS.md descreve ADRs como parte da hierarquia de documentos, mas
não há nenhum. Decisões que já foram tomadas e discutidas — JPA como modelo de domínio, quarentena em
vez de exclusão, catálogo como fonte das propriedades de pasta, exclusão confinada à biblioteca —
vivem só na memória e em comentários.
*Entrega:* rastreabilidade. Quando alguém (ou você daqui a um ano) perguntar "por que não usa
lixeira do sistema?", a resposta existe escrita.

**A3. Configuração default é Windows-first, mas o Docker é Linux.** `application.properties` aponta
para `./tools/bin/ffmpeg.exe`; o Dockerfile instala `ffmpeg` do apt. Funciona, porque a imagem
sobrescreve por variável de ambiente, mas o default do arquivo mente sobre o alvo.
*Entrega:* menos armadilha para quem clonar o repositório em Linux/macOS e rodar direto.

### Complexidade média — semanas

**A4. Apenas 16 testes de integração para um produto que move arquivos.** A lógica está bem coberta
por unidade, mas o que quebra na vida real é o encontro com o sistema de arquivos: atributo
somente-leitura (visto ontem), caminho longo, arquivo bloqueado, unidade removida no meio da
operação, acentuação, links. Hoje isso só aparece em produção — na sua máquina.
*Entrega:* confiança para distribuir. Um distribuidor não pode descobrir com o usuário final que
pasta de celular vem com `ReadOnly`.

**A5. `shared` é o maior domínio do projeto.** Cem classes, incluindo `CatalogFile`, `Execution`,
`Movement`, `Photo`, `Video` — as entidades centrais. Isso é o esperado num kernel compartilhado, mas
é também o lugar onde tudo que não tem dono acaba caindo. Vale uma revisão periódica com uma pergunta
simples: *este tipo é usado por três ou mais domínios?* Se não, ele tem dono e deveria morar lá.
*Entrega:* evita o destino comum de projetos assim, em que `shared` vira um segundo `util` e a
modularidade some por dentro.

**A6. Não há API para automação.** `/api/**` é autenticado por sessão de formulário com CSRF, o que é
correto para a tela, mas fecha a porta para script, integração ou app móvel: não existe token.
*Entrega:* um caminho de extensibilidade. Também é pré-requisito para qualquer cliente que não seja o
navegador — inclusive um app de celular, se o produto for por aí.

### Complexidade alta — meses

**A7. Uma instalação, uma biblioteca.** `WATCH_FOLDER` é um `AppSetting` global único. Usuários
existem e têm papéis, mas todos veem a mesma coleção; trocar de biblioteca significa reconfigurar a
instalação. Para uso pessoal isso é suficiente. Para distribuição, é a primeira pergunta de quem tem
fotos em dois HDs — ou de uma família com duas pessoas.
*Entrega:* multi-biblioteca destrava tanto o caso "meus discos" quanto o caso "minha família", e é
uma decisão estrutural: mexe em catálogo, inventário, quarentena e telas.

**A8. Processamento no mesmo processo da aplicação.** Inventário, hashing, thumbnails, conversão de
vídeo e geocodificação disputam a mesma JVM que serve as telas. Já há locks para não se atropelarem,
mas uma conversão pesada compete com a navegação do usuário.
*Entrega:* responsividade previsível sob carga. É a diferença entre "travou" e "está processando em
segundo plano" — e essa percepção define review de produto.

---

## Produto

### Complexidade baixa — dias

**P2. Nenhuma ação destrutiva pede confirmação com contagem, exceto a que fizemos ontem.** Excluir
duplicados, purgar quarentena e desfazer organização são irreversíveis ou custosos, e a experiência do
menu do explorer (dizer "132 arquivos, 4,2 GB" antes do botão vermelho) deveria ser o padrão.
*Entrega:* consistência e menos arrependimento — barato de fazer, alto valor percebido.

**P3. Não há como exportar um diagnóstico.** Quando algo falha, a evidência está espalhada entre log,
tela de execuções e banco. Ontem, achar a causa exigiu ler `workspace/logs`, consultar o Postgres e
cruzar com atributos de arquivo no disco.
*Entrega:* um botão "exportar diagnóstico" (versão, configurações não sensíveis, últimas execuções,
trecho de log) transforma um relato vago de usuário em algo acionável. Sem isso, suporte à distância
é adivinhação.

### Complexidade média — semanas

**P4. Instalar exige Docker ou Maven + PostgreSQL.** O README pede banco criado à mão, com role e
permissões. Isso é razoável para quem desenvolve e proibitivo para quem só quer organizar fotos.
*Entrega:* o passo que separa "projeto no GitHub" de "produto". Um executável via `jpackage` com
Postgres embarcado (ou H2/SQLite como alternativa local) tira a barreira inteira.

**P5. Não há atualização.** Subir de versão é trocar o jar e torcer para as migrations rodarem. Não
há verificação de versão nova, nem aviso, nem rollback.
*Entrega:* ciclo de vida. Software distribuído que não sabe se atualizar envelhece na máquina do
usuário — e a versão instalada vira a versão eterna.

**P6. O catálogo não tem backup próprio.** Existe exportação (`CatalogExportService`), mas não há
"faça backup agora" nem restauração guiada. O catálogo é o trabalho acumulado: metadados extraídos,
hashes, geolocalização, histórico de movimentos. Perder o Postgres é perder tudo isso, mesmo com os
arquivos intactos.
*Entrega:* proteção do ativo que o produto constrói. É o que separa "reinstalei e recomecei do zero"
de "reinstalei e continuei".

**P7. Só duas telas usam o que o catálogo sabe.** Timeline e Mapa exploram metadados; o resto é
gestão. Há riqueza subaproveitada: câmera, dimensões, duração, subcategoria, data confiável.
*Entrega:* busca avançada e coleções salvas ("fotos de 2008 sem GPS", "vídeos acima de 1 GB") usam
dados que já existem, sem processar nada de novo. É o melhor retorno por esforço do produto hoje.

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
organização. Prioriza A4, P4, P6, P7. O diferencial é a integridade: mover arquivo com verificação
byte a byte, undo, trilha de auditoria. Ninguém no mercado de consumo faz isso.

**Caminho 2 — Substituto do Google Fotos local.** Público: família que quer sair da nuvem. Prioriza
P4, P8, P9, A7. Exige a camada de encantamento e multiusuário de verdade. Concorrência forte
(Immich, PhotoPrism) e barra alta de UX.

**Caminho 3 — Motor de organização para profissionais.** Público: fotógrafo, arquivista, quem recebe
cartão de memória toda semana. Prioriza A6 (API), P7, regras de ingestão automáticas. Menor público,
maior disposição a pagar, e é onde a arquitetura atual já está mais pronta.

Minha leitura: o **Caminho 1** é o que exige menos desvio do que já existe, e P4 (instalador) é o
gargalo comum aos três — enquanto instalar exigir criar role no PostgreSQL, nenhum deles acontece.

---

## Sequência sugerida

Ordem por dependência e risco, não por valor isolado:

1. **A3** (defaults de configuração) — pré-requisito de qualquer distribuição e custa horas.
2. **A1** (fumaça de telas) — antes de crescer, evitar que o crescimento quebre em silêncio.
3. **P3** (diagnóstico) e **P6** (backup) — o que torna possível dar suporte e sobreviver a um erro.
4. **P4** (instalador) — o portão. Sem ele, o resto fica em uso pessoal.
5. **A4** (integração com o sistema de arquivos) — antes de expor o produto a discos que você nunca viu.
6. **P7** (busca e coleções) — primeiro ganho de produto que não exige arquitetura nova.
7. Só então escolher entre **A7** (multi-biblioteca), **P8** (álbuns) e **A6/P9**, conforme o caminho.

## O que eu não recomendaria agora

Dizer não também é sugestão:

- **Reescrever o front-end em SPA.** Thymeleaf + JS incremental está sustentando 18 telas com custo
  baixo. Trocar por React resolveria problemas que o projeto ainda não tem e criaria os que ele não
  quer.
- **Microsserviços.** O monólito modular está bem separado; o gargalo é processamento, e isso se
  resolve com fila e worker (A8), não com rede entre serviços.
- **Trocar o PostgreSQL.** Ele sustenta o modelo com folga. O que atrapalha é a *instalação* dele
  (P4), não o banco.
- **Reconhecimento facial (P10) antes de existir instalador.** Recurso vistoso num produto que
  ninguém consegue instalar é esforço mal colocado.