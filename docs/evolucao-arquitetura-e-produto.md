# Evolução do Nimbus File Manager — arquitetura e produto

Documento **vivo** de sugestões, não de decisões. O que virar decisão desce para um ADR em
`docs/adr/`; o que virar estado atual pertence ao README.

**Item concluído é removido daqui**, e não marcado como feito: a lista existe para dizer o que falta,
e histórico é assunto do git. Os identificadores (`A4`, `P2`, …) **não são renumerados** quando algo
sai, para que uma conversa antiga que cite `P4` continue apontando para o mesmo item.

Escrito a partir de uma varredura do código em 2026-07-31, com o produto na versão 5.26. O critério
que orienta todas as recomendações é a intenção declarada: **o produto está amadurecendo para ser
distribuído**. Isso muda o peso das coisas — o que hoje é "detalhe de ambiente do Jorge" vira
"primeira impressão de um estranho", e o que hoje é "eu sei que precisa reiniciar" vira suporte.

---

## Arquitetura

### Complexidade média — semanas

**A4. Poucos testes de integração para um produto que move arquivos.** A lógica está bem coberta por
unidade, e há 18 testes de integração — mas só 4 encostam num sistema de arquivos real, e nenhum
cobre o que de fato quebra na vida real: atributo somente-leitura, caminho longo, arquivo bloqueado
por outro processo, unidade removida no meio da operação, acentuação, links. Hoje isso só aparece em
produção — na máquina de quem instalou.
*Entrega:* confiança para distribuir. Um distribuidor não pode descobrir com o usuário final que
pasta de celular vem com `ReadOnly`.

**A5. `shared` nunca passou por revisão de dono.** São 100 classes, incluindo `CatalogFile`,
`Execution`, `Movement`, `Photo`, `Video` — as entidades centrais. Não é mais um outlier em tamanho
(`duplicate` tem 99 e `metadata` 92), mas continua sendo o lugar onde tudo que não tem dono acaba
caindo, e a revisão sugerida aqui nunca aconteceu. A pergunta é simples: *este tipo é usado por três
ou mais domínios?* Se não, ele tem dono e deveria morar lá.
*Entrega:* evita o destino comum de projetos assim, em que `shared` vira um segundo `util` e a
modularidade some por dentro.

**A6. Não há API para automação.** `/api/**` é autenticado por sessão de formulário com CSRF, o que é
correto para a tela, mas fecha a porta para script, integração ou app móvel: não existe token.
*Entrega:* um caminho de extensibilidade. Também é pré-requisito para qualquer cliente que não seja o
navegador — inclusive um app de celular, se o produto for por aí.

### Complexidade alta — meses

**A7. Uma instalação, uma biblioteca.** `WATCH_FOLDER` é um `AppSetting` global único. Trocar de
biblioteca já é uma operação suportada e segura (`LibrarySwitchService` cancela o que está rodando,
valida a pasta nova e limpa o catálogo), mas continua sendo *troca*: as duas coleções nunca coexistem,
e todos os usuários veem a mesma. Para uso pessoal isso é suficiente. Para distribuição, é a primeira
pergunta de quem tem fotos em dois HDs — ou de uma família com duas pessoas.
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

**P2. Duas ações destrutivas ainda confirmam sem dizer o tamanho do estrago.** Excluir duplicados e
apagar da quarentena já abrem diálogo com contagem. Faltam as duas de organização: *Executar* e
*Desfazer* pedem confirmação com uma frase fixa (`org.executeConfirm`, `execution.undoConfirm`), sem
dizer quantos arquivos serão movidos nem quanto isso pesa.
*Entrega:* consistência e menos arrependimento — barato de fazer, alto valor percebido.

### Complexidade média — semanas

**P4. O instalador nunca foi executado de verdade.** Todo o código existe: o perfil `installer`
monta a imagem por `jpackage` e já copia `tools/postgresql` para dentro dela quando os binários estão
presentes; a aplicação baixa esses binários sozinha (seção em Config, automático no Windows, botão
manual), cria o cluster em `<workspace>/database/cluster`, gera a senha, escolhe a porta com
retentativa, recusa cluster de versão maior sem tocar nele e para o servidor no fechamento. O que
falta é **rodar isso**: até hoje toda execução foi pela IDE ou pelo Maven, onde a decisão é
`DEVELOPMENT_BUILD` e o caminho empacotado nunca é exercitado.
*Entrega:* o passo que separa "projeto no GitHub" de "produto".

As fases abaixo são independentes e valem uma de cada vez; cada uma só faz sentido depois da anterior:

- **P4.1. Gerar a imagem e conferir o layout.** `mvn -Pinstaller package` com `tools/postgresql`
  presente, e verificar na imagem produzida que existe `app/tools/postgresql/bin` e que
  `ClusterLayout.bundledBinaries` resolve para `<launcher>/app/tools/postgresql/bin`. Hoje essa
  resolução só é afirmada por teste unitário — nenhuma imagem real foi inspecionada.
- **P4.2. Primeira execução na própria máquina de build.** Subir pelo launcher e acompanhar o
  caminho inteiro: marcador de instalação reconhecido, cluster criado, senha gerada, porta escolhida,
  migrations aplicadas, tela abrindo. É onde aparecem os erros que a IDE esconde.
- **P4.3. Máquina limpa.** Sem JDK, sem PostgreSQL e sem ffmpeg no PATH. Além de funcionar, importa
  *o que a pessoa vê* enquanto centenas de MB de binários são baixados — e o que acontece quando não
  há internet nenhuma.
- **P4.4. Atualizar e desinstalar sem perder o cluster.** Instalar por cima de uma instalação
  existente e depois desinstalar. `PGDATA` é dado crítico: guarda o catálogo inteiro, com anos de
  hashes perceptuais e localizações resolvidas. Um desinstalador que leve o workspace junto apaga
  tudo em silêncio, e esse caminho nunca foi exercitado.
- **P4.5. Instalador de verdade (MSI).** O tipo padrão é `app-image` — uma pasta que o usuário
  precisa colocar em algum lugar por conta própria. `installer.type=msi` já está previsto no perfil,
  mas exige WiX no PATH e nunca foi rodado. Sem esta fase o produto se distribui como zip.

**P5. Não há atualização.** Subir de versão é trocar o jar e torcer para as migrations rodarem. Não
há verificação de versão nova, nem aviso, nem rollback.
*Entrega:* ciclo de vida. Software distribuído que não sabe se atualizar envelhece na máquina do
usuário — e a versão instalada vira a versão eterna.

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
organização. Prioriza A4, P4, P7. O diferencial é a integridade: mover arquivo com verificação
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

1. **P4.1 e P4.2** (gerar a imagem e subir por ela) — o portão, e as duas fases mais baratas: o
   código já existe, falta descobrir se funciona.
2. **P4.3 e P4.4** (máquina limpa, atualizar/desinstalar) — o que separa "funciona aqui" de
   "funciona na casa dos outros", com P4.4 sendo o de maior risco: erra e apaga o catálogo alheio.
3. **A4** (integração com o sistema de arquivos) — antes de expor o produto a discos que você nunca viu.
4. **P7** (busca e coleções) — primeiro ganho de produto que não exige arquitetura nova.
5. Só então escolher entre **A7** (multi-biblioteca), **P8** (álbuns) e **A6/P9**, conforme o caminho.

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