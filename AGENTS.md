# Regras de Código — Nimbus File Manager

> **Documento de referência permanente do projeto.**

Este documento define as políticas permanentes de desenvolvimento do Nimbus File Manager. Seu objetivo é manter consistência arquitetural, qualidade, previsibilidade e facilitar revisões humanas e por IA.

Contém apenas regras **permanentes**, derivadas do código real e não de preferência pessoal. Informações que mudam com frequência (métricas, cobertura, versão, funcionalidades, stack) pertencem ao README, nunca a este documento.

## Hierarquia dos documentos

Em caso de conflito, prevalece nesta ordem:

1. `editor/.editorconfig` — regras mecânicas de edição.
2. Formatter do Eclipse (`.settings/org.eclipse.jdt.core.prefs`) — formatação Java.
3. Este documento — políticas permanentes de desenvolvimento.
4. ADRs — decisões arquiteturais específicas, em `docs/adr/` (um arquivo por decisão, ex.: `docs/adr/0001-titulo.md`).
5. README — estado atual do projeto (métricas, cobertura, funcionalidades, stack, requisitos).

Uma **regra nova que conflite com o código atual** deve ser decidida explicitamente antes de entrar — ou o código se ajusta à regra, ou a regra registra a exceção.

`AGENTS.en.md` é **tradução deste documento**, não um documento à parte: não entra na hierarquia acima, e onde os dois divergirem prevalece este. Ela existe para quem não lê português e por isso **não** é incluída pelo `CLAUDE.md` — o agente lê o original, e o contexto de instrução nunca carrega a mesma regra duas vezes. As duas mudam no mesmo commit; quem cobra isso é o `AgentsTranslationTest`, que quebra o build quando este arquivo muda sem a tradução acompanhar.

---

# Princípios

- Clareza antes de esperteza.
- Código simples vence código complexo.
- Segurança antes de performance; performance antes de micro-otimizações.
- Testabilidade faz parte do design.
- Evitar duplicação de lógica.
- Comentários explicam o **porquê**; o código explica o **como**.
- Toda regra nasce de um problema recorrente, derivada do código real — não de preferência pessoal.

---

# Estilo de código

## Escopo

As regras valem igualmente para **produção** (`src/main/java`) e **testes** (`src/test/java`). Formatação, espaçamento vertical, imports e nomes aplicam-se às classes de teste sem exceção.

## Regras mecânicas (`editor/.editorconfig`)

Os artefatos de configuração do editor vivem na pasta `editor/` (fora da raiz, como referência versionada — por morar num subdiretório, o `.editorconfig` **não é aplicado automaticamente** a `src/`: ele documenta a regra, e quem a torna obrigatória é este documento): `editor/.editorconfig` (regras mecânicas, fonte canônica) e `editor/eclipsejava.importorder` (ordem de *Organize Imports*). Resumo do `editor/.editorconfig`:

- Codificação UTF-8; fim de linha CRLF; **sem newline final** (`insert_final_newline = false`); espaços à direita removidos (exceto em `.md`).
- Indentação: **tab** em `java`, `xml`, `html`, `css`, `js`; **espaço** em `sql` (4), `json`/`yml`/`yaml` (2) e `md` (2).
- Largura máxima de 120 colunas apenas em Java.

**Sem newline final — o último byte do arquivo é a `}`, não `\n`.** A regra é `insert_final_newline = false`, e vale para o **caractere** de fim de linha, não só para linhas em branco visíveis: um arquivo terminado em `}\n` **viola** a regra tanto quanto um terminado em `}\n\n`. Em `git diff` a conformidade aparece como `\ No newline at end of file` na última linha. Linhas em branco **entre** blocos, essas sim, são parte do estilo (ver Espaçamento vertical).

**Text blocks Java (`"""`):** as regras de espaços à direita e indentação **não se aplicam ao interior** de um text block. O conteúdo entre `"""` é significativo e regido pelas regras do próprio Java (whitespace incidental / `\s`), não pelo editor. Trim ou reindentação automática deve **preservar o interior — nunca reescrevê-lo**. São muito usados nas queries dos repositórios (`@Query("""…""")`), então um trim ingênuo linha a linha corromperia o SQL.

**Cuidado ao detectar text block por varredura:** um delimitador **fecha e reabre na mesma linha** — `""", countQuery = """` (usado em `MediaFingerprintRepository` e `MapRepository`). Uma varredura que trate "qualquer `"""` na linha" como fim do bloco **inverte a paridade** dali em diante, passando a tratar interior de query como código (e código como interior). O estado correto **alterna a cada ocorrência** de `"""`, não uma vez por linha. Reindentar uma linha de query por engano insere **tab literal** na string e dispara `java:S2479`.

## Formatter (Eclipse, Ctrl+Shift+F)

A formatação mecânica do Java é responsabilidade **exclusiva** do formatter do Eclipse (Ctrl+Shift+F), configurado no próprio projeto em `.settings/org.eclipse.jdt.core.prefs` (chaves `org.eclipse.jdt.core.formatter.*`, versionadas junto com o código) e consistente com o `editor/.editorconfig`. Especificidades úteis ao escrever:

- **Código em 120 colunas**, contadas **da coluna 0** com **tab = 4**.
- **Comentários em 80 colunas**, contadas **a partir da coluna em que o comentário começa** — o `/` de `//`, `/*` ou `/**` — e **não** da coluna 0 (é o `count_line_length_from_starting_position` do Eclipse). Ver *Limite de 80 dos comentários* abaixo: as duas larguras usam origens diferentes, e confundi-las é a causa mais comum de varredura errada.
- Continuação: linhas quebradas indentam **2 níveis** (tabs).
- Chaves K&R: `{` no fim da linha; `} else {` e `} catch` na mesma linha do `}`.
- No máximo **1** linha em branco consecutiva; **1** antes de cada método; **nenhuma** entre campos consecutivos; imports em grupos separados por 1 linha em branco.
- O formatter não insere linha ao final do arquivo (casa com o `editor/.editorconfig`).
- **Uma anotação por linha** (`alignment_for_annotations_on_type=49`: uma por linha, forçado). Quem decide isso é a
  chave de *alinhamento*, não o `insert_new_line_after_annotation_on_type` — esta diz o que vem depois de uma
  anotação, aquela diz quantas cabem na linha. Com o default `0` (não quebrar), o Eclipse rejuntava `@Slf4j` e
  `@Service` a cada Ctrl+Shift+F mesmo com o `insert_new_line_*` em `insert`, e mexer em `join_wrapped_lines` não
  resolve (foi testado e não teve efeito).
- **Argumentos de anotação são quebrados** (`alignment_for_arguments_in_annotation`). Sem essa chave o Eclipse usa seu default — *não quebrar* — e um `@Operation(summary = …, description = …)` do OpenAPI vira uma linha de 200+ colunas a cada Ctrl+Shift+F, desfazendo qualquer quebra feita à mão.

> **Onde o formatter é configurado:** no *project scope*, em `.settings/org.eclipse.jdt.core.prefs` — as chaves `org.eclipse.jdt.core.formatter.*` que o Eclipse aplica por cima do profile do workspace, com o conjunto **completo** gravado (via *Properties → Java Code Style → Formatter → Enable project specific settings*), para que nenhuma chave dependa da máquina de quem abre o projeto. Esses arquivos são **versionados por exceção explícita** no `.gitignore` (`.settings/*` ignorado, com `!` para `org.eclipse.jdt.core.prefs`, `org.eclipse.jdt.ui.prefs` — o `formatter_settings_version` — e `org.eclipse.core.resources.prefs`, que fixa o UTF-8 da regra de codificação); o resto de `.settings/` continua fora, por ser gerado pelo m2e. **Chave ausente cai para o profile do workspace** — foi assim que anotações longas passaram a violar as 120 colunas.
>
> **Cuidado ao regravar pelo diálogo:** o Eclipse regrava o arquivo inteiro a partir do "Unmanaged profile" e, nesse caminho, devolve `comment.count_line_length_from_starting_position` para o default `false` — o que mudaria o limite dos comentários de 80-a-partir-do-comentário para 80 absolutas e reflowaria todo Javadoc indentado do projeto. Depois de mexer no diálogo, **conferir que ela voltou a `true`** (é o regime em que o código está: comentários com 1 tab chegam a 84 colunas absolutas, com 2 tabs a 88).
>
> Não há plugin de formatação no `pom.xml` (nem `formatter-maven-plugin` nem `spotless`): **nada no build reprova formatação**, o que torna a verificação manual (ver *Verificação mecânica*) obrigatória.

### Limite de 80 dos comentários

O limite de 80 dos comentários é medido **a partir da coluna inicial do próprio comentário**, com **tab = 4**. Um Javadoc indentado por 1 tab pode, portanto, chegar a **84 colunas absolutas** sem violar nada — medir a partir da coluna 0 produz centenas de falsos positivos e foi a causa das varreduras anteriores errarem.

Em orçamento de caracteres, o limite equivale a:

| Forma | Prefixo | Máximo do texto |
| --- | --- | --- |
| Corpo de Javadoc/bloco (` * texto`) | `* ` | **77** caracteres |
| Comentário de linha (`// texto`) | `// ` | **77** caracteres |
| Javadoc de uma linha (`/** texto */`) | `/** ` + ` */` | **73** caracteres |

Regras de reflow, todas observáveis no código já formatado:

- O parágrafo é **refluído por inteiro, de forma gulosa** (cada linha recebe o máximo de palavras que couber) — não basta quebrar a linha que estourou: a quebra **propaga** até o fim do parágrafo.
- São **fronteiras de parágrafo** (nunca se juntam ao texto vizinho): a linha `*` em branco, `<p>`, `<ul>`/`</ul>`/`<ol>`/`</ol>`/`<li>`, e cada tag de bloco (`@param`, `@return`, `@throws`, `@see`, …).
- Tags inline `{@link …}`, `{@code …}` e `{@literal …}` são **unidades atômicas**: cabem inteiras na linha ou descem inteiras para a próxima.
- O interior de `<pre>` e `{@snippet}` é **verbatim** — nunca refluir.
- Um Javadoc de uma linha que estoure vira bloco de várias linhas; o caminho inverso **não** existe (o formatter não recolhe um bloco em one-liner).

### Limite de 120 do código

Medido em **colunas absolutas a partir da coluna 0**, com **tab = 4**. Quebra com indentação de continuação de **2 tabs**, preenchendo cada linha ao máximo.

São **exceções legítimas** (o formatter não as quebra; deixar como estão):

- O **interior de text blocks** (`"""…"""`) — já é território exclusivo do Java, ver acima.
- Linhas de `import` / `import static`.
- Uma linha cuja largura vem de **um único token indivisível** — literal `String` longo, anotação com `description`/`example` extenso — em que nenhuma quebra possível traria a linha para dentro de 120.

Fora dessas três, uma linha acima de 120 é violação e deve ser quebrada.

## Verificação mecânica

Regra mecânica não se verifica "de olho" — as varreduras anteriores falharam justamente aí. Antes de encerrar qualquer tarefa, **medir**, em **todo o projeto** e não só nos arquivos tocados, e chegar a zero em cada item:

1. **Newline final** — nenhum arquivo termina em `\n` (último byte é o do conteúdo). **Ficam de fora os arquivos não autorais**: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (gerados pelo Maven Wrapper) e `LICENSE` (texto legal verbatim) — o estilo do projeto não reescreve arquivo de terceiro.
2. **Comentários** — nenhum comentário acima de 80 colunas **medidas do início do comentário**, exceto o interior de `<pre>`/`{@snippet}` (verbatim por definição).
3. **Código Java** — nenhuma linha acima de 120 colunas absolutas fora das três exceções acima.
4. **Espaços à direita** — zero, exceto em `.md` e no interior de text blocks.
5. **Fim de linha** — CRLF.

Os itens 1, 4 e 5 valem para **todo tipo de arquivo** (`.java`, `.html`, `.css`, `.js`, `.sql`, `.xml`, `.json`, `.yml`), pela seção `[*]` do `editor/.editorconfig`; os itens 2 e 3 são só de Java.

## Espaçamento vertical

Não é expressável no `editor/.editorconfig` nem imposto de forma confiável por formatter/linter — é convenção do projeto, verificada em revisão:

- Uma linha em branco **entre grupos de passos lógicos** dentro de um método. Statements fortemente relacionados (um mesmo passo) ficam **juntos**; a linha em branco separa um grupo do próximo.
- Uma linha em branco **antes do `return`** quando há um passo anterior distinto; **não** quando o `return` conclui o mesmo grupo lógico.
- **Ficam juntos** (sem linha em branco entre membros) apenas statements da **mesma família/tipo**: grupo de `when(...).thenReturn/thenAnswer`, grupo de `verify(...)`, grupo de `assertThat(...)`/`Assertions.*`, **declarações de variáveis do mesmo tipo** (ex.: dois `byte[]`, dois `PhotoPerceptualFingerprint`), ou **repetições da mesma operação** (ex.: dois `service.compute(file)`). **Entre grupos, uma linha em branco** — cada família/tipo/passo lógico distinto separa, **inclusive dentro do arrange** (criar o arquivo, montar o input e construir o serviço são passos separados, pois têm tipos/propósitos diferentes). Famílias diferentes de verificação também se separam entre si — ex.: um grupo de `assertThat(...)` e um grupo de `verify(...)` levam linha em branco entre eles.
- As regras acima valem para **blocos normais** (corpo de método, `if`/`for`/`while`/`try`). **Pular** o interior de **lambdas**, **switch expressions** (`case X ->`) e **expressões multi-linha**: ali o statement faz parte de uma construção coesa.
- Uma linha em branco **após a `{` que abre a classe**, antes do primeiro membro; **não** há linha em branco após a `{` que abre um método. Records e enums seguem a mesma regra quando têm corpo (inclusive enums que só listam constantes).
- **Sem** linha em branco antes da `}` que fecha um método ou a classe.
- Uma linha em branco **entre membros** (métodos, construtores, grupos de campos).
- **Nunca** duas linhas em branco consecutivas.
- Imports em grupos separados por uma linha em branco (estáticos primeiro), mantendo a ordenação gerada pelo Eclipse.

## Convenções

- Identificadores, comentários e Javadoc em **inglês**. Em documentação, o idioma segue o leitor a quem o documento se dirige: **pt-BR** nos ADRs e nos documentos de `docs/`, que registram decisão e discussão internas; **inglês** no README, que é a porta de entrada de um produto distribuído. Este documento é pt-BR e normativo, com tradução em `AGENTS.en.md` (ver *Hierarquia dos documentos*). Um documento novo escolhe um idioma e não mistura.
- **Injeção de dependência por construtor** (`@Autowired` no construtor), campos `private final`.
- **No máximo 7 parâmetros por método ou construtor** (regra Sonar S107). Ao ultrapassar esse limite, agrupar parâmetros de dados coesos em um *Parameter Object* (`record`/DTO) ou reavaliar a responsabilidade da classe/método. Para construtores de injeção de dependências, priorizar a divisão de responsabilidades da classe em vez de encapsular dependências em um objeto.
- **Sem tipos aninhados.** `class`, `record`, `enum` e `interface` (funcionais ou não) são declarados em **arquivo próprio (top-level)**, nunca aninhados dentro de outra classe. Um *Parameter Object* extraído para resolver o item acima também nasce em arquivo próprio.
- **Menor visibilidade possível.** Todo tipo, método, campo e construtor tem a **menor visibilidade que atende ao uso real**: usado só na própria classe → `private`; só no próprio pacote → package-private (sem modificador); `protected` só quando há herança real; `public` **apenas** quando há uso legítimo cross-package (ou exigência de framework/override — handlers `@GetMapping`/`@Bean`/`@Override`, acessores de `record`/`@Entity`, binding de `@ConfigurationProperties`, `@Test`). **Nunca ampliar visibilidade para acomodar um teste ou uma dependência mal-colocada** — co-localizar o teste no pacote do alvo (usando acesso package-private) ou corrigir a camada, em vez de tornar `public`. **Não alterar o modificador de acesso sem necessidade real:** ao mover/refatorar/renomear, **preservar a visibilidade original**; só mudar quando o uso legítimo de fato mudou, e nesse caso na direção de **restringir**, não ampliar. Um `public` que só é referenciado dentro do próprio pacote é débito a corrigir. **Exceção: constantes de dados** — ver regra abaixo.
- **Constantes de dados numa classe `<Domínio>Constants`.** Constantes de dados (`static final` de `String`/numérico/`boolean` — chaves de preferência/setting, identificadores de página, limites, mensagens, nomes bem-conhecidos) **não** ficam inline na classe de comportamento (service, controller, componente, advice, helper). Cada domínio tem uma classe `<Domínio>Constants` (geral) num package `<domínio>/application/constants` (ex.: `organization/application/constants/OrganizationConstants`), simétrico ao `<domínio>/application/dto`, com **todas as constantes de contrato `public`**, referenciadas por `import static`. Um domínio **pode ter mais de um holder** nesse mesmo package `constants` quando as constantes formam um grupo coeso e auto-descritivo — ex.: `ExecutionMessages` (chaves de mensagem), `WorkspaceFolders` (nomes de pasta), `FingerprintAlgorithm` (ids de algoritmo), `UsnReason` (reason codes) — em vez de um grab-bag no `<Domínio>Constants`. **Todo holder de constantes mora em `<domínio>/application/constants`.** Exceção: constantes de **protocolo nativo** ligadas a glue de infraestrutura (ex.: `WindowsUsnConstants`/`WindowsRdcwConstants`, na FFM/kernel32) ficam co-localizadas com o código nativo em `infrastructure/**/windows`, não sobem para `application/constants`. Isso dá um lar único e previsível às constantes, evita que uma constante seja "tornada `public` por necessidade" dentro de um controller/service (o que gerava acoplamento cross-feature), e mantém as classes de comportamento focadas em comportamento. Um consumidor de outro domínio referencia `SettingsConstants.WATCH_FOLDER`, não a classe de comportamento dona. **Não são constantes de domínio** (e ficam onde estão): `LOGGER`/`serialVersionUID`; valores de binding de `@ConfigurationProperties`; constantes de `enum`/`record`/`@Entity`; nomes de bean/`@Qualifier` de `@Configuration` (contrato do framework, no dono natural); e **constante `private` que nenhuma outra classe lê** — parâmetro de algoritmo declarado junto da fórmula que o usa (`C1`/`C2` do SSIM, raio da Terra, lado da amostra), tamanho de buffer/lote, timeout, tolerância, nome da própria view ou redirect do handler, e atraso/período de um `@Scheduled` do próprio bean. O teste é a **visibilidade**: se é `private` e ninguém fora da classe (nem o teste) a referencia, ela não é contrato, não pode gerar acoplamento cross-feature — que é o problema que esta regra existe para evitar — e mover para um holder só afastaria o valor do único trecho que o explica. **Constante `package-private` ou `public` lida por outra classe de produção continua violação**: ela é contrato e o lugar dela é o holder. `package-private` lida apenas pelo **teste co-localizado** segue sendo o padrão aceito (é o que a regra de visibilidade manda fazer em vez de ampliar para `public`); e se ninguém lê de fora, o certo é `private`. Constantes transversais (usadas por infra do `shared`) vão para `shared/application/constants/SharedConstants`.
- **Imports sempre organizados** antes de concluir a tarefa. Remover todos os imports não utilizados, adicionar os necessários e organizar os imports para produzir exatamente o mesmo resultado que o Eclipse (*Organize Imports* / Ctrl+Shift+O) geraria, respeitando a configuração do projeto em `editor/eclipsejava.importorder` (grupos `java`, `javax`, `org`, `com`, e por fim os demais — `br.com.*`, `jakarta.*`, `lombok.*`… — num único grupo alfabético; estáticos primeiro; grupos separados por uma linha em branco). Nunca deixar imports não usados nem utilizar ordenação diferente da gerada pelo Eclipse.
- **Sem nomes totalmente qualificados inline:** referenciar tipos e membros estáticos pelo nome simples com `import` (ex.: `AtomicBoolean`, não `java.util.concurrent.atomic.AtomicBoolean`; `doThrow(...)` com `import static`, não `org.mockito.Mockito.doThrow(...)`).
- **Nomes de teste** em camelCase descrevendo o comportamento verificado (ex.: `raisesWhenAKeyIsAbsentFromTheBaseBundle`).
- **Variável não nomeada (`_`) para binding não usado.** Todo parâmetro de lambda que **não é referenciado** no corpo usa o nome não nomeado `_`, nunca um nome real que se ignora (`e`, `x`, `ignored`) nem um `_` prefixado (`_x`). A mesma regra vale para os demais bindings não usados: variável de `catch`, componentes de pattern (`instanceof`/`switch`) e variável de `for`. Ex.: `(_, _) -> {}`, `.map(_ -> Optional.empty())`, `catch (IOException _)`. O projeto usa Java 25, que suporta `_` plenamente, então o parâmetro fica autoexplicativo (sinaliza "de propósito não usado") e não gera warning de variável não utilizada. Quando **mais de um** binding não usado coexiste no mesmo escopo, todos são `_` (a linguagem permite repetir `_`).
- **Javadoc** apenas para explicar o *porquê* de decisões não óbvias, nunca para repetir o óbvio.

---

# Arquitetura

O código é **agrupado por domínio** (`catalog`, `inventory`, `organization`, `duplicate`, `metadata`, `geolocation`, `media`, `execution`, `security`, `timeline`, `settings`, `thumbnail`, `processing`, `quarantine`, `conversion`, `map`, `telemetry`, `statistics`, `preferences`, `notification`, `time`) e, **dentro de cada domínio**, separado em **camadas hexagonais** (ports & adapters). O bootstrap (`NimbusFileManagerApplication`) fica na raiz do pacote.

## Camadas por domínio

Cada domínio `<d>` se organiza em três camadas:

- **`<d>/domain`** — núcleo de negócio. `domain/model` (entidades `@Entity` + value objects), `domain/enums` (enums do domínio) e `domain/repository` (interfaces Spring Data = **ports**, com `domain/repository/projection` para as projections que os ports retornam).
- **`<d>/application`** — casos de uso e orquestração. Services, coordenadores, runners e helpers de regra (`resolver`, `rule`, `batch`, `watch`, `explorer`, `fingerprint`…), além de **`application/dto`** (todo DTO/record de dados do domínio — request, response, view, raw).
- **`<d>/infrastructure`** — adapters. `infrastructure/rest` (controllers REST), `infrastructure/web` (controllers MVC + view-models), `infrastructure/persistence` (repositórios JDBC custom — adapters concretos sem interface Spring Data) e `infrastructure/config` quando o domínio tem `@Configuration`/`@ConfigurationProperties` próprios. Glue externo (ProcessRunners, FFM/nativo, adaptadores HTTP, provedores de e-mail) mora em `infrastructure`.

**Direção da dependência (inviolável):** `infrastructure → application → domain`. O `domain` não conhece framework de entrega nem adapters; a `application` não conhece `infrastructure`. Por isso as projections retornadas por um port ficam em `domain/repository/projection` (nunca em `infrastructure`).

## Shared kernel (`shared`)

Modelo e adapters **transversais** (usados por ≥3 domínios ou sem dono único) vivem em `shared`, com a mesma estrutura de camadas: `shared/domain/model` (ex.: `CatalogFile`, `Execution` e família, `Movement`, `Photo`, `Video`, `MediaMetadata`, `StatusMessage`), `shared/domain/enums` (ex.: `FileType`, `LifecycleStatus`, `ExecutionStatus`), `shared/domain/repository` (ports sobre entidades do kernel, ex.: `CatalogFileRepository`, `ExecutionRepository`), `shared/application/dto` (DTOs genéricos como `PagedResponse`, `SizeResponse`) e `shared/infrastructure` (advices/handlers globais, config de bootstrap). Utilitários gerais (`util`, `i18n`, `concurrent`) ficam em `shared`. **Domínios dependem de `shared`, nunca o contrário.** Uma entidade/enum de dono único fica no `domain` do dono; só sobe para `shared` quando de fato é cross-cutting.

## Controllers (`infrastructure/rest` · `infrastructure/web`)

- Recebem requisições, validam entrada e orquestram chamadas; **nunca** implementam regra de negócio.
- Advices/handlers globais transversais (`RestExceptionHandler`, `AppViewModelAdvice`) vivem em `shared/infrastructure`. Como não há mais um pacote único `api`/`web`, o `basePackages` desses advices **lista explicitamente** os pacotes `*.infrastructure.rest` (REST) ou `*.infrastructure.web` (MVC). Ao criar um domínio com controller novo, **incluir seu pacote nessa lista** — senão o advice/handler deixa de disparar para ele.

## Services (`application`)

- Concentram regra de negócio e coordenam transações; não conhecem a camada de entrega.

## Repositories (`domain/repository` · `infrastructure/persistence`)

- Acesso a dados exclusivamente; nenhuma regra de negócio (ver Persistência).

## Entities (`domain/model`)

- Representam persistência; evitar lógica complexa. Excluídas da cobertura via `**/domain/model/**`.

## DTOs (`application/dto`)

- Transporte de dados; sem comportamento.
- **Todo DTO sem lógica** (record/classe puramente de dados) **reside no package `application/dto` do seu domínio** (genéricos em `shared/application/dto`). Fica fora da medição de cobertura (o JaCoCo exclui `**/dto/**`), evitando cobrar cobertura de acessores/records gerados. Um Parameter Object extraído por causa da regra S107 também nasce nesse package.

## Configuração (`infrastructure/config`)

- **Configurações funcionais, agrupadas ou pertencentes a um namespace do Nimbus File Manager** (`nimbus-file-manager.*`) devem usar `@ConfigurationProperties` — classe/record tipado, registrado em `@EnableConfigurationProperties`, injetado por construtor (ex.: `NimbusFileManagerProperties`, `BoundaryDatasetProperties`, `InventoryWatchProperties`). Uma classe dedicada quando o namespace é próprio; um componente do agregado quando cabe num record existente. O binding tipado centraliza defaults, valida na inicialização e mantém a testabilidade (o teste constrói a properties sem contexto Spring). O wiring do Spring (config de bootstrap) e as properties do agregado vivem em `shared/infrastructure/config`.
- **`@Value` só é aceitável** para valores isolados de infraestrutura ou propriedades nativas do Spring, **quando uma classe dedicada não trouxer ganho real de coesão ou testabilidade**.

---

# Arquitetura hexagonal e abstrações

A arquitetura hexagonal deve ser aplicada de forma **pragmática, não cerimonial**.

- **Isolamento do domínio.** Nenhuma classe em `**/domain/**` depende de `**/application/**`, `**/infrastructure/**`, framework, tecnologia ou sistema externo. A dependência aponta sempre para dentro (`infrastructure → application → domain`); domínios dependem de `shared`, nunca o contrário. O que um port de repositório **retorna ou recebe é contrato do domínio**: projections, filtros e value objects de consulta vivem em `<domínio>/domain/repository/projection`, nunca em `application/dto`. *Verificável:* nenhum `import` de `.application.`/`.infrastructure.` dentro de `src/main/java/**/domain/**` (o build deve manter isso em zero). **A regra é sobre código de produção.** Um **teste de integração co-localizado** num package `domain` pode importar o service de `application` que ele exercita — é justamente o serviço que escreve pelo repositório sob teste (ex.: `DuplicateExclusionRepositoryIntegrationTest`, `MovementSummaryQueryIntegrationTest`). Isso **não** é inversão de dependência: o artefato publicado continua com o `domain` isolado.
- **Portas e adaptadores nas fronteiras reais.** Adaptadores de I/O externo (ffmpeg/exiftool/mediainfo, HTTP, filesystem, e-mail, glue nativo) ficam **só** em `infrastructure`. Suporte de domínio que atravessa inevitavelmente a fronteira do framework (ex.: `ClockHolder`, ponte estática para os callbacks `@PrePersist`/`@PreUpdate` das entidades, que não recebem injeção) mora no domínio (`shared/domain`), não em `application`.
- **Abstração só onde paga.** Criar uma porta/interface quando ela isola uma fronteira real — um sistema externo, uma tecnologia que pode mudar, ou um ganho concreto de testabilidade. **Não** criar abstração por rito: uma interface com um único implementador que apenas embrulha o framework, sem ponto de variação nem valor de teste, é cerimônia — evitar.
- **Exceções pragmáticas conscientes.** As entidades JPA (`@Entity`) **são** o modelo de domínio e os repositórios Spring Data (`extends JpaRepository`) **são** os ports — vivem no `domain` mesmo carregando anotações de tecnologia. Não se separa um modelo POJO das entidades JPA nem se cria adapter só para embrulhar o Spring Data: o boilerplate de mapeamento não se paga numa aplicação (ao contrário de uma biblioteca de domínio complexo). É decisão explícita — o isolamento do primeiro item vale para dependências **entre classes do projeto**; JPA/Spring dentro do `domain` é a fronteira pragmática aceita.

---

# Responsabilidade única

Cada classe deve ter uma responsabilidade predominante. Quando responsabilidades distintas aparecerem, preferir extrair métodos, componentes ou novas classes. Evitar classes que concentrem persistência, cache, integração, logging e regra ao mesmo tempo.

**Nome reflete a responsabilidade, não a feature:**

- **O nome da classe reflete a responsabilidade real e mais ampla — nunca uma feature específica que ela apenas atende.** Uma classe geral/compartilhada usada por várias telas não leva o prefixo de uma delas. Ex.: o endpoint que entrega detalhe/conteúdo de mídia ao lightbox (usado por timeline, mapa, arquivos, duplicados, quarentena) é `MediaContent*`, **não** `TimelineMedia*` — o prefixo de uma feature confunde com aquela feature. Se um nome de feature deixou de descrever o que a classe faz (ela cresceu para servir outras), **renomear**.
- **Lógica compartilhada/cross-feature não mora dentro de uma classe de uma feature.** Utilidades consumidas por mais de uma feature (ex.: streaming de mídia — range/content-type/nome seguro) vivem numa classe neutra da sua própria responsabilidade (ex.: `MediaContentService`), **não** escondidas num serviço de feature (ex.: `TimelineService`) só porque surgiram ali primeiro. Uma classe de feature nunca acumula utilitários gerais que outras features também usam — isso vira acoplamento cross-feature disfarçado.

---

# Dívida técnica e limpeza

- **Sem código morto.** Não deixar métodos, classes, campos, variáveis, imports, CSS, JavaScript, recursos ou dependências sem uso. Código não referenciado é removido, não comentado.
- **Remover o obsoleto ao substituir.** Quando uma implementação substitui outra, a antiga sai no mesmo passo — nada de "recurso antigo" convivendo com o novo.
- **Sem lógica duplicada entre classes.** A mesma regra/conversão/validação/tratamento vive num único lugar. **Reutilizar a implementação existente antes de criar uma nova.**
- **Classe nova só com responsabilidade própria.** Não criar abstrações artificiais só para poupar poucas linhas; consolidar apenas quando for de fato uma responsabilidade única e coesa (ver [Responsabilidade única](#responsabilidade-única)).
- **Comentários e Javadocs em inglês, atualizados e corretos.** O comentário explica o *porquê*; mantê-lo em dia com o código. Remover ou corrigir comentários/Javadocs órfãos, desatualizados ou incorretos. Todo comentário/Javadoc novo nasce em inglês.
- **Comentário não repete informação volátil.** Comentário e Javadoc **nunca reproduzem um valor ou fato que vive em outro lugar e pode mudar sem que ninguém releia o comentário**: default de configuração ("o padrão de 90 dias"), limite/janela editável em Settings, número de itens ou classes, percentual de cobertura, versão de dependência, nome de arquivo gerado, lista de telas/domínios existentes. O valor mora num único lugar (constante, `AppSetting`, `@ConfigurationProperties`, README) e o texto se refere a ele **pelo nome** — "o default documentado na constante", "a janela configurada em Settings" — em vez de reproduzi-lo. *Motivo:* um comentário que repete o valor vira mentira silenciosa na primeira alteração, e quem alterou não tem como saber que precisava atualizar o comentário; a referência pelo nome continua verdadeira para sempre.

  Fica de fora o caso em que o número **é** o assunto daquele trecho e está declarado ali: o CRF de um perfil de qualidade, o valor esperado de uma asserção, um exemplo derivado de uma fórmula documentada logo acima (`para n = 5: 10%, 30%, …`). Nesses casos o valor está no código ao lado, muda junto e o comentário explica a escolha, não o número.

---

# Persistência

- **Migration que muda a forma de um dado carrega o dado junto.** Criar coluna é aditivo e não exige nada; mas **renomear, mudar o tipo, dividir, juntar, mover para outra tabela ou remover** uma coluna ou tabela obriga a migration a **transportar os dados existentes** no mesmo arquivo — `UPDATE`/`INSERT ... SELECT` antes do `DROP`, nunca só o DDL. *Motivo:* a aplicação é instalada por pessoas cujo banco já está populado — catálogo de anos, hashes perceptuais que custaram horas, localizações resolvidas. Uma migration que só mexe na estrutura passa limpa num banco vazio de teste e apaga o trabalho de quem usa o produto, sem erro e sem aviso. O banco vazio é o caso raro; o populado é o normal.
  Vale também para a travessia de um backup antigo: é a migration que sabe como o dado de ontem vira o dado de hoje, e sem esse transporte nenhuma restauração através de versões é possível.
- **Nunca `@Lob` em `String`:** colunas `TEXT` mapeadas com `@Lob` são lidas como Large Object e quebram fora de transação (erro 500 em auto-commit). Deixar `String` sem `@Lob`.
- **Acesso a dados só pela camada de repositório:** serviços e componentes **não** acessam `JdbcTemplate`/`NamedParameterJdbcTemplate` nem `EntityManager` diretamente. Vale inclusive para operações em massa: a query nativa entra como `@Modifying(nativeQuery = true)` num repositório Spring Data, ou como método de um repositório custom `@Repository` sobre `NamedParameterJdbcTemplate` quando o padrão set-based/streaming justifica evitar a sessão JPA. O componente fica só com a orquestração (parse, progresso, transação).
- **Repositório mora na camada certa do seu domínio:** as **interfaces Spring Data** (`extends JpaRepository`/`Repository`) são **ports** e residem em `<domínio>/domain/repository` (com `domain/repository/projection` para as projections que retornam). Os **repositórios JDBC custom** (`@Repository` sobre classe concreta, sem interface Spring Data) são **adapters** e residem em `<domínio>/infrastructure/persistence`. **Não existe pacote central de repositórios.** Um repositório **cross-feature** (usado por mais de um domínio) mora no domínio **dono da entidade** que ele gerencia — os ports sobre entidades do kernel ficam em `shared/domain/repository`. O scan cobre a aplicação inteira (`@EnableJpaRepositories(basePackageClasses = NimbusFileManagerApplication.class)`), então não é preciso registrar cada subpacote.
- **Casamento de prefixo de caminho com `LIKE` (Windows/PostgreSQL/HQL):** ao filtrar "descendentes de uma pasta" por `LIKE`, no PostgreSQL o `\` é o **escape padrão do `LIKE`** e nomes de arquivo contêm `_`/`%` (curingas) — um padrão ingênuo `like concat(:pasta, :sep, '%')` **falha para caminhos Windows** (só casa por acidente na raiz de unidade, `D:\`). Construir o padrão com `PathUtils.descendantLikePattern(pasta, separador)` (garante separador final, escapa `\ % _`) e usar `like :pattern escape '\'` — no **HQL** o backslash de um parâmetro bindado é tratado como **literal**, então o `escape '\'` explícito é obrigatório (ao contrário do SQL nativo). **Validar via Hibernate** (teste de integração Testcontainers inserindo caminhos com backslash como dados — roda no CI Linux, pois são só strings); nunca confiar só em probe JDBC cru, que usa o `LIKE` nativo e mascara o comportamento do HQL, nem em testes que usam só `/` (não cobrem Windows).

---

# Clone limpo executa

Todo o projeto pressupõe que quem chegou **acabou de clonar**, numa máquina onde nada foi preparado à mão: sem ffmpeg, sem PostgreSQL, sem pasta de ferramentas, sem workspace, sem variável de ambiente. Nesse estado, **a suíte roda e a aplicação sobe** — baixando na própria execução o que faltar. É a mesma promessa do instalador: copiar e abrir.

O que isso obriga:

- **Nada de pré-requisito manual.** Se um recurso precisa de um binário externo, quem o busca é a aplicação (como já fazem o ffmpeg e o PostgreSQL embarcado), não um passo de README que alguém tem que lembrar de executar.
- **Toda pasta é criada sob demanda**, no primeiro uso. Nenhum caminho de execução pressupõe diretório preexistente.
- **Teste que depende de binário externo se auto-pula** (`@EnabledIf`) em vez de falhar. Falhar por ausência de dependência externa transforma "clonei agora" em build vermelho, e ensina a ignorar vermelho. O CI instala o que precisa e, lá, esses testes rodam de verdade.
- **Nenhum caminho depende de artefato que só existe na máquina de quem desenvolveu.** Se um teste passa localmente porque havia uma pasta baixada meses atrás, ele não está testando o que parece — foi assim que a busca de binários ficou quebrada no empacotado sem ninguém notar.
- **Escrita vai para o workspace**, nunca para dentro da instalação: um programa instalado pode estar numa pasta somente-leitura, e artefato baixado é dado do usuário, não parte do programa.

*Motivo:* o produto está sendo distribuído. "Funciona aqui" é uma afirmação sobre a máquina de uma pessoa; "funciona num clone limpo" é uma afirmação sobre o produto — e é a única que vale para quem instala.

---

# Manipulação de arquivos

- **Somente arquivos físicos:** nunca seguir symlink, junction ou atalho `.lnk`. Usar `PhysicalFilePolicy.isProcessable`; nunca `FileVisitOption.FOLLOW_LINKS`.
- **Move seguro centralizado:** todo movimento de arquivo **do usuário** passa por `SecureFileMove` (baseline SHA-256 + verificação byte-a-byte + rollback) — vale para organização, dedup e undos. Nunca `Files.move` direto num arquivo do usuário.
- **Exceção legítima:** artefatos internos/regeneráveis que **não** são mídia do usuário podem usar `Files.move` direto — por exemplo, mover o arquivo temporário de um download de dataset ao destino, ou um thumbnail gerado para o cache. A garantia forte (hash + rollback) existe para dados insubstituíveis do usuário, não para artefatos que o sistema regenera.

---

# Internacionalização

- Nenhum texto exibido ao usuário pode ficar hardcoded: nos templates via `#{chave}`, no backend via `message(chave, args...)`. Todo texto vive nos bundles (`messages.properties` pt-BR padrão + `messages_en.properties`).
- Backend **sem fallback no código** — a chave existe só nos bundles; chave ausente lança `NoSuchMessageException`.
- Toda nova chave existe em **todos os idiomas suportados**. A paridade é travada no build por testes dedicados (chaves `backend.*` usadas no código e paridade pt×en) — o build quebra se faltar.

---

# Responsabilidades Front-end × Back-end

O **back-end é a única fonte de verdade do domínio**; o front-end (templates Thymeleaf, JS, CSS) é responsável **apenas por apresentação, interação e renderização**.

**Back-end** decide e entrega pronto: regras de negócio, validações, permissões, cálculos, estados do domínio, classificações, decisões, parâmetros de negócio, mensagens de negócio e **toda a internacionalização**. Nenhum texto de negócio fica hardcoded em `Controller`, `Service`, `Exception`, `Validator`, DTO, `enum` ou qualquer classe Java — resolve-se via `MessageSource` (ver Internacionalização), com o texto vivendo em `messages.properties`/`messages_en.properties`.

**Front-end** faz **só**: renderizar telas, exibir informações, interação do usuário, estado exclusivamente visual, layout, navegação e componentes. Não conhece regra de negócio nem traduz domínio — apenas exibe os textos já resolvidos que a API ou o template entregam.

**Proibido no front:**
- **Tradução de domínio** — `switch`/`if`/ternário/`Map`/objeto/`enum`/array que traduza status, tipo, categoria, motivo, mensagem ou descrição. A tradução vive nos bundles do back-end; a API/o `MessageSource` entrega o texto pronto.
- **Regra de negócio** — decisão por status, cálculo, classificação, bloqueio, filtro de negócio, ordenação por regra, validação de domínio, ou combinação de campos para inferir um estado.
- **Decisão de permissão** — o front nunca decide "pode editar/excluir/desfazer/mover/baixar/executar". O back-end informa explicitamente via campos (`canEdit`, `canDelete`, `canUndo`, `canMove`, `canDownload`, …).
- **Comparação por texto traduzido** — nunca `if (status === "Processado")` nem `if (message === "Arquivo já existe")`. Comparar sempre por **código/enum/flag/identificador técnico**, jamais pelo texto exibido.
- **Duplicação de domínio** — listas de status/categorias/tipos/motivos não se replicam no front; a API as fornece.

Contratos devem **entregar a decisão pronta** em vez de campos crus para o front decidir — ex.: preferir `{"status":"PROCESSING","canDelete":false,"canRetry":true}` a `{"status":"PROCESSING","owner":true,"locked":false}` deixando o front combinar.

**Pode permanecer no front:** texto exclusivamente visual sem conceito de domínio (nome de botão, placeholder, tooltip de componente, label fixo de interface) no i18n do front; e CSS, layout, organização visual, componentes, estado visual, animações e comportamento exclusivo da interface.

---

# Interface e preferências

- **Preferência de tela/UI, por usuário:** toda opção que o usuário escolhe numa tela é gravada por usuário (`UserPagePreference`/`UserPagePreferenceService`) e reaplicada ao reabrir a tela. Nunca resetar para o default a cada visita.
- **Configuração global da aplicação:** parâmetros que valem para a instalação inteira (não por usuário) vivem em `AppSetting`/`AppSettingService` (key-value tipado, editável na tela de configurações, semeado com defaults). Ex.: fuso horário da aplicação, provedores, limites.
- Não confundir as duas: o que é escolha pessoal de visualização é `UserPagePreference`; o que é comportamento da aplicação é `AppSetting`.
- **Ação que o sistema não executou avisa o usuário em diálogo, com o motivo.** Toda ação disparada pelo usuário que o sistema recusa, ignora ou cumpre apenas em parte — recurso ocupado por outra operação, item que mudou de estado entre a listagem e o clique, arquivo ausente, permissão insuficiente — termina num **modal** dizendo *por que* e, quando houver, *o que fazer a respeito*. Não basta registrar no log (o usuário não lê log) nem devolver contadores para a tela interpretar: uma linha de status com "0 apagados, 0 erros" é lida como sucesso. O **motivo vem pronto do back-end** — o contrato carrega uma mensagem já localizada e a tela apenas a exibe, como manda [Responsabilidades Front-end × Back-end](#responsabilidades-front-end--back-end). Quando a ação faz o que foi pedido, nada de modal: interromper para confirmar sucesso é ruído.
- **Ações secundárias** usam `.button.secondary` (com borda) de `components.css`, nunca link ad-hoc. Ao criar/alterar UI, validar contraste no tema **claro e escuro** reaproveitando as variáveis de tema.

---

# Observabilidade

Níveis de log:

- **ERROR** — somente falhas que exigem investigação.
- **WARN** — comportamento inesperado, porém recuperável.
- **INFO** — eventos relevantes do ciclo de vida.
- **DEBUG** — detalhes técnicos.
- **TRACE** — investigação profunda.

**Nunca registrar stack traces (nem ERROR) para situações esperadas** — por exemplo, falhas provocadas por um shutdown em andamento são DEBUG, não ERROR.

---

# Performance

- Não otimizar prematuramente; medir antes de otimizar.
- Evitar O(n²) desnecessário e consultas repetidas.
- Preferir processamento incremental e streaming quando aplicável.

---

# Testes

- **Regra base:** toda funcionalidade nova ou alteração vem acompanhada de teste (unitário; e de integração quando envolver banco, HTTP ou processo externo). Nenhuma mudança pode baixar a cobertura.
- Toda lógica condicional nova testa os caminhos **positivo, negativo e limite**.
- Os testes validam **comportamento observável**. Nunca escrever teste apenas para aumentar percentual de cobertura.
- **Caminho de teste que passa por normalização é absoluto e real (`@TempDir`).** Quando o código sob teste chama `toAbsolutePath()`/`normalize()` (ou compara `Path` com o que o serviço devolve), o teste **não** pode montar o caminho com literal de unidade Windows (`Path.of("D:", "library")`, `"D:\\trash"`): no **CI Linux** isso é um caminho *relativo* de um único segmento, que a normalização prefixa com o diretório de trabalho do runner — o teste passa no Windows e quebra no CI. Usar `@TempDir` (parâmetro do método ou do `@BeforeEach`) e derivar tudo dele com `resolve`: sendo absoluto de verdade, a normalização é identidade e a asserção vale nos dois SOs. Literal com letra de unidade só é aceitável quando o caminho **não** passa por normalização — quando ele é apenas repassado (`String`/`Path` opaco) ou casado por `eq(...)` contra o mesmo objeto.

## Exclusões legítimas de cobertura

Classes fora da medição (configuradas no `pom.xml` e espelhadas nas exclusões do Sonar), por não serem unit-testáveis de forma significativa — são cobertas por testes de integração ou verificação manual:

- `NimbusFileManagerApplication` (bootstrap) e `**/infrastructure/config/**` (fiação Spring).
- `**/domain/model/**`, `**/dto/**` e `**/application/constants/**` (dados sem lógica — entidades, DTOs e holders de constantes do domínio).
- `**/repository/**` e `**/*Repository` (contratos de acesso a dados).
- `**/*ProcessRunner` (glue de processo externo: ffmpeg/exiftool/mediainfo).
- `**/GeoBoundariesSource` (adaptador HTTP de download da base geográfica) e `**/windows/**` (glue nativo FFM/kernel32, só-Windows).
- `**/infrastructure/desktop/**` (glue AWT de bandeja: `SystemTray`/`TrayIcon`/`PopupMenu` e o repasse ao `explorer.exe` — não há bandeja no CI headless, onde toda chamada é no-op, e o que ele faz num desktop só se vê num desktop).

Lógica de verdade **nunca** mora nessas classes excluídas — fica no serviço que as usa, que é testado. As metas numéricas de cobertura e o estado atual vivem no README.

## Piso de cobertura (catraca)

A cobertura **nunca regride**: o bloco de qualidade do README registra o **piso vigente** das cinco métricas JaCoCo (instrução, branch, linha, método, classe), e nenhuma tarefa pode encerrar abaixo dele. Os números moram no README — este documento fixa só a política, porque o piso muda a cada avanço e métrica não pertence a um documento permanente.

Como operar a catraca:

- **Antes de encerrar**, rodar a suíte completa e comparar as cinco métricas com o piso do README. Ficou abaixo de qualquer uma → a tarefa **não está pronta**; cobrir o que se perdeu antes de entregar.
- **Subiu?** Atualizar o piso no README para os valores novos, no mesmo commit. É isso que faz a catraca andar — piso desatualizado permite regredir de graça.
- **O piso é chão, não meta.** O README também registra a **meta** perseguida; alcançá-la promove a meta a piso e uma nova meta é definida.

### A medição varia entre execuções

Duas execuções seguidas da mesma suíte, sem uma linha alterada, dão números diferentes — observado em até **0,16 ponto** no branch e ~0,03 nas demais. Duas causas, ambas do próprio projeto:

- **Execução paralela.** `src/test/resources/junit-platform.properties` roda classes de teste concorrentemente. Quais ramos de código compartilhado são exercitados muda de execução para execução: cache que ora popula ora acerta, caminho de contenção, timeout que ora dispara.
- **Testes que se auto-pulam.** Os que dependem do ffmpeg (`@EnabledIf`) pulam quando `tools/ffmpeg/bin` não existe, e os métodos que eles cobririam contam como descobertos. A pasta é gitignored, então um worktree ou um clone novo mede diferente da árvore principal.

Como operar diante disso:

- Uma métrica **poucos centésimos** abaixo do piso não é, por si só, regressão. Antes de escrever teste atrás do número, **verificar se o código novo tem parte descoberta** (relatório do JaCoCo por classe/método). Se não tiver, é ruído: **remedir** em vez de inventar teste.
- Uma queda que **se repete** entre execuções, ou que aponta para classe/método novo sem cobertura, é regressão de verdade e vale a regra acima.
- **Não baixar o piso** por causa de oscilação, e **não arredondar** a casa decimal: a queda pode ser real, e uma régua mais grossa esconderia justamente o que a catraca existe para pegar.
- Medir sempre com `clean` e com a árvore principal completa (ver *Piso exige build limpo*); comparar números tirados em condições diferentes produz conclusão errada.

### Recalcular o piso

Um recurso grande traz caminhos que **nenhum teste honesto alcança** — `catch` de I/O que exige o sistema operacional negar algo, guarda que só dispara por corrida, `continue`/`break` que o compilador só alcança por uma condição impossível. Como o piso é percentual sobre o projeto inteiro, esse código **derruba a métrica sem que nada tenha regredido**. Nesse caso — e **só** nesse — o piso pode ser regravado abaixo do anterior.

Não é atalho, e a ordem importa:

1. **Colher primeiro o que é honesto, em qualquer ponto do projeto.** A métrica é global, então uma lacuna legítima em código antigo paga a conta de um caminho inalcançável no código novo — e a busca não se limita ao que a tarefa tocou. Recalcular antes dessa varredura é afrouxar a régua com trabalho por fazer.
2. **Classificar o que sobrou, linha a linha.** Alvo alcançável por um teste que afirma comportamento observável não é resíduo, é tarefa. Confirmar no relatório do JaCoCo que a linha é de fato inalcançável antes de aceitá-la: uma linha marcada como perdida pode ser um salto que o compilador roteia por outro caminho, e nesse caso o teste "que faltava" não moveria nada.
3. **Registrar a natureza do resíduo no README** — quantas linhas e de que tipo — e não só os números novos. Piso menor sem essa conta é indistinguível de regressão.
4. **Gravar os valores medidos** em build limpo, no mesmo commit.

Métrica que **subiu** sobe o piso junto, sempre: recalcular não é sinônimo de baixar as cinco.

Continua valendo a regra base — **nunca** se escreve teste artificial para mover percentual. Se a única forma de segurar o piso for instanciar construtor privado por reflection ou exercitar getter, o certo é recalcular e declarar o resíduo.

### Código inalcançável e `@CoverageGenerated`

Código que **nenhum teste honesto alcança** pode sair da medição, anotado com `@CoverageGenerated("motivo")` (em `shared/application`). O nome carrega "Generated" porque esse é o único gancho que o JaCoCo oferece — ele filtra membros anotados com anotação cujo nome simples contenha `Generated` e retenção `CLASS`/`RUNTIME`, o mesmo mecanismo do `lombok.Generated`. Nada ali é gerado.

**Cabe em dois casos, e o motivo vai no argumento:** fiação de framework que existe só para o contêiner construir o objeto (construtor `@Autowired` que apenas repassa para outro que o teste chama direto), e caminho de falha de I/O que exige o sistema operacional negar algo — permissão, volume ilegível, handle que morre no meio de uma varredura.

**Não cabe** em delegação de uma linha, em ramo apenas trabalhoso de montar, nem em nada que uma reestruturação tornaria alcançável. Entre anotar e reestruturar, **reestruture**: perseguir cobertura já encontrou `return` inalcançável e guarda redundante neste projeto, e apagar isso valeu mais que esconder. A anotação também **não alcança bloco** — `catch` e `if` só saem da medição se o método inteiro sair, o que esconderia o caminho coberto junto; nesses casos, ou o caminho vira alcançável, ou fica como resíduo declarado.

A catraca **não** autoriza atalho: continua valendo que teste valida comportamento observável e que **nunca se escreve teste só para mover percentual** (ver a regra base acima). Se a única forma de subir uma métrica for teste artificial — instanciar construtor privado por reflection, exercitar getter, afirmar o óbvio — o certo é **deixar a métrica onde está** e registrar o motivo, não inventar teste. Código legitimamente inalcançável (caminho de erro de I/O dependente de SO, guarda anti-instanciação, override exigido por contrato mas nunca chamado) é resíduo aceito, não dívida.

---

# Qualidade estática (Sonar)

- **Toda tarefa deve terminar sem criar nenhuma issue nova no Sonar.** Rodar a análise ao final e comparar o total — e a contagem **por regra** — com o estado anterior.
- Issues **preexistentes** podem permanecer apenas quando **não pertencem ao escopo** da tarefa.
- Qualquer **aumento por regra** — inclusive uma issue nova surgida como **efeito colateral** de outra correção — deve ser **investigado e eliminado antes de encerrar**. Não se entrega tarefa que introduz débito, ainda que trivial.
- Falsos positivos e casos legítimos (padrão idiomático, exigência de biblioteca/spec, hotspot seguro por design) são **marcados como aceitos/revisados no Sonar com justificativa**, nunca "resolvidos" com código artificial.
- **Caso aceito recorrente — `java:S3516` em handler MVC que devolve redirect.** Um handler `@PostMapping` de tela devolve **sempre o mesmo `redirect:`**, porque o que muda entre os caminhos é a *flash attribute* (`success`/`error`), não o destino — a página de origem se rerenderiza com a mensagem. O Sonar lê isso como "método sempre retorna o mesmo valor" e a issue é **aceita**, nunca contornada. **Não refatorar para um único `return`** (extraindo as guardas para um método que devolve a chave da mensagem): isso fecha a issue, mas deixa o handler diferente de todos os irmãos do projeto, e consistência vale mais do que zerar uma regra cujo padrão já foi decidido. Já há vários assim aceitos (`SettingsGeodataWebController`, `OrganizationWebController`, `SettingsWebController`). Ao criar um handler novo desse tipo, **aceitar a issue no Sonar** como as anteriores.
- **Caso aceito recorrente — `java:S2479` em `@Query("""…""")`.** A indentação do text block das queries JPQL deixa whitespace significativo na string, e o Sonar reclama do caractere não escapado. É **idiomático e proposital** (a query fica legível no código), então a issue é **aceita no Sonar**, nunca contornada com concatenação ou escapes artificiais. Já há dezenas assim aceitas nos repositórios. Uma S2479 **nova** nesses arquivos, porém, é sinal de que alguém **reindentou uma linha da query** — nesse caso o certo é reverter a reindentação, não aceitar a issue.

---

# Análise de bytecode (SpotBugs + find-sec-bugs)

O Sonar lê o código-fonte; o SpotBugs lê o **bytecode**. Sozinho ele repetiria quase tudo o que o analisador Java do Sonar já cobre — quem justifica a ferramenta é o plugin **find-sec-bugs**, que traz os detectores de segurança que faltavam: *taint analysis* para path traversal, injeção, criptografia fraca e XXE, sobre uma aplicação que recebe caminhos do usuário, executa processos externos e baixa e verifica arquivos.

Roda em profile próprio, fora do build do dia a dia — que é o executado dezenas de vezes por dia:

```bash
./mvnw -Pspotbugs verify
```

- **Toda tarefa termina com `-Pspotbugs verify` verde.** O goal `check` reprova o build no primeiro achado, como a régua do Sonar reprova por issue nova.
- Um achado se resolve **corrigindo o código** ou **excluindo com justificativa** em `spotbugs-exclude.xml`. Não há terceira via: `@SuppressFBWarnings` espalhado pelas classes tira a decisão do único arquivo onde ela é revisada em conjunto.
- **Exclusão global apenas quando o que o detector reporta é regra deste projeto**, não defeito. Exclusão de caso analisado fica **presa à classe** e nunca é promovida a global.
- Cada `<Match>` carrega **o porquê**, conferido contra o código. Entrada sem justificativa é débito disfarçado de configuração — é assim que uma ferramenta passa a ser ignorada.

## Casos aceitos recorrentes

Os padrões abaixo já estão excluídos, com o motivo registrado no próprio `spotbugs-exclude.xml`. Um achado **novo** de um desses tipos, em situação diferente da descrita lá, é sinal de que a justificativa deixou de valer: revisa-se a exclusão, não se amplia:

- **`EI_EXPOSE_REP` / `EI_EXPOSE_REP2`** — a injeção por construtor com campos `final`, que é regra deste documento, é exatamente o que o detector reporta.
- **`SPRING_ENDPOINT`** — marcação informativa de handler, não defeito.
- **`CRLF_INJECTION_LOGS`** — o log é um arquivo no workspace de quem roda a aplicação, e o que ele registra são caminhos, que são o assunto do produto.
- **`COMMAND_INJECTION`** — todo `ProcessBuilder` daqui recebe `List<String>`, que não passa por shell; não existe linha de comando onde injetar.
- **`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`** — `Path.getFileName()`/`getParent()` só são nulos na raiz de unidade; a guarda seria ramo inalcançável, que este documento já recusa. Desreferência nula continua coberta pelo Sonar.
- **`REC_CATCH_EXCEPTION` e os `THROWS_METHOD_*`** — o `catch (Exception)` é deliberado: uma passagem agendada que deixa exceção escapar mata o próprio timer para sempre.
- **`IMPROPER_UNICODE`** — comparações que já passam por `Locale.ROOT`.

---

# Warnings de compilação e análise estática

Antes de concluir qualquer tarefa, validar os arquivos criados ou modificados quanto a warnings de compilação e análise estática.

- Não introduzir novos warnings.
- Corrigir warnings surgidos como efeito colateral da própria tarefa, mesmo quando não forem reportados por todas as ferramentas de análise.
- Não ocultar warnings com `@SuppressWarnings`, exclusões de análise ou alteração das configurações das ferramentas, salvo quando houver justificativa técnica documentada.
- Fechar corretamente recursos que implementem `AutoCloseable`, preferencialmente com `try-with-resources`.
- Declarar `serialVersionUID` em exceções e demais classes serializáveis quando aplicável.
- Usar a variável não nomeada `_` para todo parâmetro de lambda, variável de `catch`, componente de pattern ou variável de `for` não utilizado (ver *Variável não nomeada* em Estilo de código → Convenções); além de deixar a intenção explícita, elimina o warning de variável/parâmetro não usado.

---

# Versionamento

A versão fica em `pom.xml` `<version>`, no formato **`MAJOR.MINOR.PATCH.BUILD`**. A classificação considera o **impacto para o usuário**, não a quantidade de arquivos modificados:

- **MAJOR** — mudança incompatível ou arquitetural profunda. Incrementa MAJOR, zera MINOR e PATCH, incrementa BUILD.
- **MINOR** — nova funcionalidade compatível. Incrementa MINOR, zera PATCH, incrementa BUILD.
- **PATCH** — correção de bug ou pequena melhoria. Incrementa PATCH e BUILD.
- **BUILD** — contador histórico sempre crescente. Refatoração, teste, doc interna ou config sem mudança de comportamento público sobem **só o BUILD**.

Quando e como aplicar:

- Alterar a versão **uma única vez por tarefa**, **só depois** da implementação concluída e revisada, e apenas se houve alteração real no repositório — nunca para análise ou diagnóstico puro.
- Rodar os testes aplicáveis **antes**; se falharem por causa da mudança, **não** incrementar. Se o ambiente não permitir rodar os testes, avisar e ainda assim incrementar se a implementação estiver concluída e revisada.
- Ao concluir, informar: versão anterior, versão nova e o motivo da classificação.

---

# Git

- **Nunca commitar sem pedido explícito** do desenvolvedor. Implementar, revisar, testar e versionar são feitos livremente; o commit é sempre uma ação solicitada.

---

# README

O README representa o **estado atual** do projeto — é onde vivem métricas, cobertura, versão, funcionalidades, stack e requisitos. Sempre atualizar quando houver mudança em funcionalidades, arquitetura pública, stack, requisitos ou cobertura.

Cobertura: ao final de todo build que rode a suíte completa (`mvn test`/`verify`), atualizar o bloco de qualidade do README com os valores do `QualitySummary` (contagem de testes e métricas JaCoCo). Não deixar os números defasados — são a referência pública de qualidade e devem refletir o último build local limpo.

**Sem data em métrica recorrente.** Blocos de métrica que se refazem a cada build (cobertura, contagem de testes, mutation score/PIT) **não levam data** — rotular como "última execução" / "most recent run", nunca "gerado em `<data>`". Uma data carimbada numa métrica recorrente vira débito imediato: envelhece no build seguinte e sugere defasagem mesmo quando os números estão atuais. **Exceção — datas de evento histórico único** (quando algo aconteceu, não um estado que se repete: ex.: o squash de migração "em 2026-07-12") **permanecem**, pois registram um fato pontual, não uma métrica.

---

# Evolução deste documento

Novas regras só entram quando resolvem um problema recorrente, eliminam ambiguidade ou representam decisão arquitetural permanente. Evitar regras temporárias ou específicas de uma única implementação. Regra que conflite com o código existente é decidida explicitamente antes de entrar.