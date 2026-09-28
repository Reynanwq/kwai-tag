# Planejamento — kwai-tag (reconstrução manual do domínio TAG)

> Objetivo: reescrever **na mão** o módulo `gubee-kwai-tag` (etiquetas Kwai) num projeto pessoal,
> simulando o fluxo real ponta a ponta — HTTP, Kafka, MongoDB, API do Kwai falsa e Hub falso —
> para **quebrar de propósito** e aprender a tratar erro, garantir ordem de mensagem e idempotência.
>
> Referência (só leitura, não copiar): `workspace-gubee/gubee-apps/gubee-kwai/gubee-kwai-tag`
> e o core `workspace-gubee/gubee-apps/gubee-tag`.

---

## 0. Regras do jogo

1. **Código escrito à mão.** Este documento dá contratos, cenários e critérios de pronto — não dá
   implementação. Consultar o projeto real só depois de tentar.
2. **Teste antes do código** no domínio (TDD). Cada bug do real vira primeiro um teste vermelho.
3. **Toda fase termina com um experimento de quebra**: provocar a falha, observar, corrigir, anotar
   no `DIARIO.md` (o que quebrou, por quê, como resolvi).
4. Um commit por passo pequeno. Mensagem no formato `fase-N: <o que>`.

---

## 1. O que vamos simular

O fluxo real, simplificado mas fiel nos pontos que doem:

```
                      POST /api/tag/packOrders
 ┌──────────────┐   {accountId, groupId, orderIds}   ┌──────────────────────────────┐
 │ hub-simulator │ ─────────────────────────────────▶ │           kwai-tag            │
 │ (core Gubee)  │ ◀── 202 Accepted ───────────────── │  TagResource                  │
 │               │                                    │    └▶ Kafka tag.download      │
 │  Mongo:       │                                    │  DownloadTagConsumer          │
 │  tag_group    │   POST /tag/package/upload/url     │    └▶ DownloadTagUseCase      │
 │  tag_package  │ ◀───────────────────────────────── │        ├ Mongo: delivery_label│
 │               │   Kafka tag.package.publisherror   │        └ KwaiClient ──────────┼──┐
 │               │ ◀───────────────────────────────── │                               │  │
 └──────────────┘                                    └──────────────────────────────┘  │
                                                                                         ▼
                                                              ┌────────────────────────────────┐
                                                              │ kwai-fake (WireMock)            │
                                                              │  /logistics/deliveryInfo        │
                                                              │  /logistics/createDelivery      │
                                                              │  /logistics/deliveryDocumentV2  │
                                                              │  cenários: NOT_FOUND→DOING→OK   │
                                                              └────────────────────────────────┘
```

Regras do Kwai que a simulação **precisa** respeitar (são elas que geram os problemas):

| Regra | Consequência no código |
|---|---|
| Sempre HTTP 200; erro vem em `result` no body | Nunca decidir sucesso pelo status HTTP |
| Sucesso = `result` 200 **ou** 1 | Tabela única de tradução `result → exceção` |
| `createDelivery` é assíncrono e **não idempotente** | Chamar 2x = 2 entregas. Precisa de guarda |
| Etiqueta só aparece depois, via polling de `deliveryDocumentV2` | Polling com backoff, sem `sleep` |
| `11011` DOING, `11012` FAILED, `11013` NOT_FOUND, `11014` ERROR | São **estado**, não erro genérico |
| `5000` service busy (não existe HTTP 429) | Rate limit se detecta pelo body |
| `440..447` auth/assinatura | Não-retryable (exceto token expirado) |

---

## 2. Stack e decisões

| Item | Escolha | Por quê |
|---|---|---|
| Linguagem / runtime | Kotlin 2.x, Java 21 | Igual ao real |
| Framework | Quarkus 3.x | Igual ao real (REST, REST Client, Mongo Panache, Kafka) |
| Build | Maven multi-módulo | Igual ao real (hexagonal por módulo) |
| Mensageria | Kafka (KRaft) em Docker + Kafka UI | Ver partições, keys, offsets, DLQ |
| Consumer | **Etapa A:** `KafkaConsumer` puro com commit manual. **Etapa B:** Parallel Consumer (fork `bz.stub.parallelconsumer`, o mesmo do `gubee-parallel-consumer`) | Entender at-least-once "no braço" antes de usar a lib |
| Banco | MongoDB 7 | Igual ao real |
| API do Kwai | **WireMock** standalone (Docker) com *scenarios* stateful | Simula DOING→READY e injeta falhas sem escrever servidor |
| Core Gubee | **hub-simulator**: mini app Quarkus próprio | Precisa de lógica (remover pedido do pacote ao receber erro) para os bugs ficarem visíveis |
| Testes | JUnit 5, MockK, Testcontainers, WireMock embutido | Unitário no domínio, integração nos adapters |
| Resiliência | SmallRye Fault Tolerance (ou resilience4j) | Circuit breaker e retry de transporte |

---

## 3. Estrutura do repositório

```
kwai-tag/
├── PLANEJAMENTO.md            (este arquivo)
├── DIARIO.md                  (o que quebrou e como resolvi — escrever ao longo das fases)
├── docker-compose.yml         (kafka, kafka-ui, mongo, wiremock)
├── wiremock/
│   ├── mappings/              (stubs JSON do Kwai)
│   └── __files/               (PDFs falsos de etiqueta)
├── pom.xml                    (aggregator)
├── tag-domain/                (modelo, portas, use cases, exceções — ZERO dependência de framework)
├── tag-kwai-client/           (REST client do Kwai + tradução de envelope)
├── tag-hub-client/            (REST client do hub: upload/url)
├── tag-repository/            (Mongo: delivery_label)
├── tag-app/                   (Quarkus: resource, consumer, publisher, config)
└── hub-simulator/             (Quarkus: POST packOrders→kwai-tag, recebe upload/url e publisherror)
```

Regra de dependência (verificar com o build): `tag-domain` não importa nada de Quarkus, Kafka, Mongo
ou Jackson. Os outros módulos dependem do domínio, nunca o contrário.

---

## 4. Contratos

### 4.1 HTTP — kwai-tag (entrada)

| Método | Path | Body | Resposta |
|---|---|---|---|
| POST | `/api/tag/packOrders` | `{sellerId, accountId, tagGroupId, orderIds[], tagType[]}` | `202 {sellerId, accountId, failedOrders[]}`; `400` se faltar campo |
| GET | `/api/tag/{accountId}/{orderId}` | — | estado do `delivery_label` (diagnóstico) — *extra, não existe no real* |
| POST | `/api/tag/{accountId}/{orderId}/retry` | — | reseta tentativas e republica — *extra, fase 9* |

### 4.2 HTTP — kwai-fake (WireMock imitando o Kwai)

Todos `POST`, todos respondem HTTP 200 com envelope `{result, message, data}`.

| Path | Body | `data` no sucesso |
|---|---|---|
| `/rest/open/api/logistics/deliveryInfo` | vazio | `{collectionTypeList, pickUpTimeList, merchantAddressList[{id,type,pickUpFlag,isDefault}]}` |
| `/rest/open/api/logistics/createDelivery` | `{orderId, senderAddressId, collectionType, pickUpTime?}` | nada |
| `/rest/open/api/logistics/deliveryDocumentV2` | `{orderId}` + header `lang` | `{deliveryId, orderId, trackingNumber, shippingLabelUrl}` |

Query params comuns: `appKey`, `merchantId`, `ts`, `version=1.0`, `sign`. Na fase 3, o WireMock deve
**recusar com `result: 443`** se `sign` estiver ausente, para obrigar a existência de um interceptor.

### 4.3 HTTP — hub-simulator (imitando o core gubee-tag)

| Método | Path | Comportamento |
|---|---|---|
| POST | `/tag/package/upload/url/{groupId}` | `{orderIds, packageType, redirectUrl, sellerId}` → cria `tag_package` |
| POST | `/simulate/group` | cria um `tag_group` e chama `packOrders` no kwai-tag (dispara o fluxo) |
| GET | `/simulate/group/{groupId}` | mostra o grupo, os pacotes e os erros |

Ao consumir `tag.package.publisherror`, o hub **remove o pedido de pacotes já existentes**
(igual ao `PublishErrorPackageImpl.removeErrorOrdersFromExistingPackages` do real) e recalcula o
status do grupo (`PROCESSING` / `PARTIALLY_COMPLETE` / `COMPLETE` / `ERROR`).

### 4.4 Kafka

| Tópico | Produtor | Consumidor | Key | Payload |
|---|---|---|---|---|
| `kwaitag.tag.download` | kwai-tag | kwai-tag | **decisão da fase 7** (real usa `accountId`) | `{orders:[{orderId}]}` + headers `ACCOUNT_ID`, `TAG_GROUP_ID`, `TAG_TYPE`, `CORRELATION_ID` |
| `kwaitag.tag.download.retry` | kwai-tag | kwai-tag | idem | idem — *fase 8, retry não-bloqueante* |
| `kwaitag.tag.download.dlq` | kwai-tag | ninguém (inspeção manual) | idem | original + headers `dlq-reason`, `dlq-attempts`, `dlq-exception` |
| `tag.package.publisherror` | kwai-tag | hub-simulator | `sellerId` | `{sellerId, groupId, failedOrders:[{orderId, errorMessageCode, errorMessageDescription}]}` |

### 4.5 Mongo

`delivery_label` (kwai-tag) — `_id = "<accountId>:<orderId>"` com escape do `:`.

| Campo | Tipo | Nota |
|---|---|---|
| accountId, orderId | String | |
| groupId | String | *novo* — resolve o bug B (reimpressão) |
| status | `ACCEPTED \| POLLING \| READY \| FAILED \| TIMEOUT` | |
| attemptCount | Int | |
| firstAttemptAt, lastAttemptAt | Instant | |
| deliveryRequestedAt | Instant? | *novo* — marca que `createDelivery` já foi chamado (bug do `11013`) |
| trackingNumber, shippingLabelUrl, failureCode, failureMessage | String? | |
| version | Long | lock otimista |

`tag_group` e `tag_package` (hub-simulator) — modelo mínimo para ver o efeito dos erros.

---

## 5. Fases

Cada fase tem: **construir** → **testar** → **quebrar** → **anotar**.

### Fase 0 — Infra local (½ dia)

- [ ] `docker-compose.yml` com Kafka KRaft (1 broker), Kafka UI, MongoDB 7 e WireMock (porta 8089,
      volume `./wiremock`).
- [ ] Criar os tópicos com **3 partições** (necessário para os experimentos de ordem).
- [ ] Aggregator Maven com os 6 módulos vazios compilando.
- [ ] Um stub WireMock trivial respondendo `{result:200}` e testado com `curl`.

**Pronto quando:** `docker compose up` sobe tudo; `mvn -q verify` passa; Kafka UI mostra os tópicos.

**Quebrar:** derrubar o Mongo com o app de pé e ver o que o health check responde.

### Fase 1 — Domínio puro + TDD (1–2 dias)

Construir em `tag-domain`, sem framework:

- [ ] Modelos: `CreateTagCommand`, `DownloadTagCommand`, `DeliveryDocument`, `DeliveryOptions`,
      `PickUpWindow`, `CollectionType`, `TagType`.
- [ ] `OrderDeliveryLabel` como **máquina de estados**: transições válidas
      `ACCEPTED→POLLING→{READY,FAILED,TIMEOUT}`; qualquer outra lança exceção.
- [ ] Hierarquia de exceções com **markers**: `RetryableException` e `NonRetryableException`
      (interfaces), e `DeliveryDocumentPending/Failed/NotFound/Error`, `DeliveryOptionsUnavailable`.
- [ ] Portas SPI: `KwaiLogisticsPort` (createDelivery, fetchDeliveryDocument, fetchDeliveryOptions),
      `TagHubPort`, `TagEventPublisher`, `DeliveryLabelRepository`, `Clock` injetado.
- [ ] Use cases: `CreateTagUseCase` (publica), `DownloadTagUseCase` (o coração).

Testes obrigatórios do `DownloadTagUseCase` (fakes em memória, **não** mocks, para o estado ficar visível):

| # | Cenário | Esperado |
|---|---|---|
| 1 | Kwai responde pronto | upload no hub, label READY, sem `createDelivery` |
| 2 | `11013` na primeira vez | chama `createDelivery` **uma** vez, lança Pending |
| 3 | `11011` | não chama `createDelivery`, lança Pending |
| 4 | `11012` | publica falha, não lança |
| 5 | Sem endereço de envio | publica falha, não lança |
| 6 | Grupo com 1 pronto + 1 pendente | o pronto sobe, Pending carrega **só** o pendente |
| 7 | Passou do timeout total | TIMEOUT, publica falha, **não** lança (senão vira loop infinito) |

**Pronto quando:** os 7 testes passam e `tag-domain` não tem nenhuma dependência além de Kotlin,
SLF4J e bibliotecas de teste.

**Quebrar:** trocar a ordem para "criar antes de consultar" e ver o teste 3 falhar. Anotar por quê.

### Fase 2 — Kwai falso com WireMock (1 dia)

Montar os stubs em `wiremock/mappings`. Usar **scenarios** (máquina de estados do WireMock) por `orderId`:

| orderId | Roteiro do `deliveryDocumentV2` | Para que serve |
|---|---|---|
| `1001` | NOT_FOUND → DOING → DOING → 200 | caminho feliz com polling |
| `1002` | 200 de primeira | etiqueta já existia |
| `1003` | NOT_FOUND → FAILED (11012) | falha terminal |
| `1004` | DOING para sempre | força TIMEOUT |
| `1005` | 11014 para sempre | erro ambíguo persistente (bug C) |
| `1006` | `result: 5000` duas vezes → 200 | rate limit / slow-down |
| `1007` | `result: 1` com dados | sucesso alternativo (F12) |
| `1008` | resposta com `fixedDelayMilliseconds: 15000` | timeout de leitura |
| `1009` | `fault: CONNECTION_RESET_BY_PEER` | falha de transporte |
| `1010` | 200 **sem** `shippingLabelUrl` | sucesso incompleto |
| `1011` | NOT_FOUND → (depois do create) NOT_FOUND **mais uma vez** → DOING → 200 | consistência eventual: prova que "consultar antes de criar" duplica entrega |

Outros stubs:
- [ ] `createDelivery` que **conta chamadas** (verificar pela API admin `/__admin/requests`).
- [ ] `deliveryInfo` para contas diferentes: com PICK_UP, só DROP_OFF, sem endereço `type=1`.
- [ ] Um stub genérico que recusa com `443` quando falta `sign`.
- [ ] Endpoint `/__admin/scenarios/reset` documentado no `DIARIO.md` para reiniciar os roteiros.

**Pronto quando:** consigo percorrer o roteiro do `1001` só com `curl`.

### Fase 3 — Client do Kwai (1–2 dias)

- [ ] REST Client Quarkus para os 3 endpoints.
- [ ] `ClientRequestFilter` que adiciona `appKey`, `merchantId`, `ts`, `version` e calcula `sign`
      (SHA-256 de `host+path+query` ordenada + secret — implementação própria, só precisa ser determinística).
- [ ] Tradução de envelope em **um lugar só**: `KwaiResultCode.toException(result, message)`.
- [ ] `deliveryDocumentV2` com envelope próprio: `11011..11014` viram exceções de estado **antes**
      da tabela genérica.
- [ ] Resolução de coleta (`deliveryInfo`): prefere PICK_UP com `pickUpFlag` e janela válida; senão
      DROP_OFF com endereço `isDefault`; senão `DeliveryOptionsUnavailable`.
- [ ] Parser da janela `"8:00-12:00"` com `Clock` e **zona explícita** (`America/Sao_Paulo`).

Testes de integração com WireMock embutido (`@QuarkusTestResource`): um por linha da tabela da fase 2.

**Quebrar:**
1. Rodar o teste da janela com o `Clock` do sistema e o container em UTC. Observar a coleta agendada
   3h errada. Corrigir com a zona explícita.
2. Colocar o retry de transporte em volta do `createDelivery` e usar o stub `1008` (lento). Contar as
   entregas criadas no `/__admin/requests`. **Resultado esperado: mais de uma.** Anotar e decidir:
   `createDelivery` não pode ter retry de transporte cego (ver técnica T5).

### Fase 4 — Persistência (1 dia)

- [ ] `delivery_label` com Panache, `_id` composto e escape do `:`.
- [ ] Lock otimista por `version` (atualização condicional: `updateOne` filtrando `_id` e `version`).
- [ ] Testcontainers Mongo.

**Quebrar:** dois threads atualizando o mesmo label ao mesmo tempo. Sem `version`: a última escrita
vence e um estado se perde. Com `version`: um deles recebe erro de concorrência. Decidir se esse erro é
retryable (sim).

### Fase 5 — Kafka "no braço" (Etapa A, 2 dias)

Antes de qualquer lib, um `KafkaConsumer` puro num loop:

- [ ] `enable.auto.commit=false`; commit **depois** do processamento (at-least-once).
- [ ] Retry em memória com backoff exponencial + jitter, contando tentativas em um header.
- [ ] Quando esgota: publica na DLQ com os headers de diagnóstico e commita o original.
- [ ] Deserialização inválida (poison pill) vai direto para a DLQ, sem travar a partição.
- [ ] `TagResource.packOrders` → `CreateTagUseCase` → producer (com `acks=all` e idempotência do producer ligada).
- [ ] MDC por mensagem: `correlationId`, `accountId`, `groupId`, `orderId`; `MDC.clear()` no `finally`.

**Quebrar:**
1. Matar o app (`kill -9`) no meio do processamento. Ao subir, a mensagem é reprocessada. Anotar
   por que isso é aceitável **só** se o processamento for idempotente.
2. Commitar **antes** de processar e repetir o kill. A mensagem some. Anotar a diferença entre
   at-most-once e at-least-once.
3. Publicar um JSON quebrado no tópico. Sem tratamento, o consumer trava na mesma mensagem para sempre.

### Fase 6 — Hub simulado e fluxo ponta a ponta (1–2 dias)

- [ ] `hub-simulator` com `tag_group` / `tag_package`, endpoint `upload/url` e consumer de `publisherror`
      com a lógica de **remover o pedido do pacote**.
- [ ] `POST /simulate/group` com os pedidos `1001,1002,1003` → acompanhar até o grupo ficar
      `PARTIALLY_COMPLETE` (1003 falhou).
- [ ] Teste de ponta a ponta com Testcontainers (Kafka + Mongo + WireMock) e Awaitility.

**Pronto quando:** o cenário acima fecha sozinho, sem intervenção, em menos de 1 minuto.

### Fase 7 — Ordem de mensagens (2 dias) — *foco do projeto*

Trocar para o Parallel Consumer (Etapa B) e rodar a matriz abaixo. Para cada linha: medir o tempo até
o grupo B completar e registrar no `DIARIO.md`.

| # | Key | processing-order | Cenário | Pergunta a responder |
|---|---|---|---|---|
| 7.1 | `accountId` | KEY | Grupo A com `1004` (DOING para sempre) e, logo depois, grupo B com `1002` na **mesma conta** | O grupo B fica preso atrás do A? Quanto tempo? |
| 7.2 | `groupId` | KEY | igual ao 7.1 | O B passa na frente? |
| 7.3 | `orderId` (1 mensagem por pedido) | KEY | igual ao 7.1 | Quanto melhora? O que se perde (visão do grupo)? |
| 7.4 | `accountId` | PARTITION | igual ao 7.1 | Bloqueia outras contas da mesma partição? |
| 7.5 | `accountId` | UNORDERED | 2 mensagens do **mesmo** pedido (duplicata do core) | Duas threads chamam `createDelivery` juntas? Quantas entregas? |
| 7.6 | `orderId` | KEY | igual ao 7.5 | A duplicata fica serializada? |

Conceitos para entender e explicar com as próprias palavras: *head-of-line blocking*, ordem por
partição vs. por chave, por que a ordem só vale **dentro** da partição, e o que um rebalance faz
com mensagens em voo.

**Decisão ao final:** escolher a key definitiva e justificar. Hipótese a validar: `orderId` com fan-out
de 1 mensagem por pedido resolve o bloqueio (7.1) e a corrida (7.5) ao mesmo tempo.

### Fase 8 — Técnicas de tratamento de erro (3 dias)

Implementar e comparar. Cada técnica tem um cenário do WireMock que a justifica.

| # | Técnica | Implementar | Cenário para provar |
|---|---|---|---|
| T1 | **Classificação** retryable / não-retryable / estado de negócio | markers + tabela `result→exceção` | 443 não re-tenta; 11011 re-tenta; 11012 publica falha |
| T2 | **Retry bloqueante** com backoff exponencial + jitter + teto | config do consumer | `1001` completa; ver intervalos no log |
| T3 | **Retry não-bloqueante** (tópico de retry com `processAfter`) | tópico `.retry` + header de agendamento | repetir 7.1: o grupo B deixa de esperar |
| T4 | **DLQ** com contexto | headers `dlq-*`; ferramenta de reprocessar da DLQ | `1005` termina na DLQ com o motivo legível |
| T5 | **Idempotência do efeito colateral** | `deliveryRequestedAt` persistido **antes** de chamar `createDelivery` (padrão "intenção antes da ação") | `1011` e `1008`: exatamente 1 `createDelivery` |
| T6 | **Timeout total de negócio** ≠ max-retries | guard por `firstAttemptAt` no use case | `1004` vira TIMEOUT em ~10 min, sem loop |
| T7 | **Circuit breaker** por conta+operação | Fault Tolerance | 20 falhas `1009` seguidas abrem o circuito; outras contas seguem |
| T8 | **Slow-down queue** para rate limit | `5000` → tópico lento com concorrência 1 | `1006` passa sem martelar o fake |
| T9 | **Isolamento de falha por item** em lote | captura por pedido; nenhum erro de 1 pedido derruba o grupo | grupo `1002+1005`: 1002 sobe mesmo com 1005 falhando |
| T10 | **Poison pill** | `skip-deserialization-errors` + DLQ | JSON inválido não trava a partição |
| T11 | **Outbox transacional** (opcional, avançado) | gravar label + evento no Mongo na mesma operação e publicar via relay | matar o app entre "salvar READY" e "publicar": nada se perde |

Para cada técnica, escrever no `DIARIO.md`: **problema → técnica → custo** (o que ela piora).

### Fase 9 — Reproduzir e corrigir os bugs do projeto real (2 dias)

Cada item: **teste vermelho primeiro**, depois a correção.

| Bug | Reproduzir com | Correção esperada |
|---|---|---|
| **A** — READY vira TIMEOUT e o hub remove a etiqueta | grupo `1002 + 1004`; esperar o timeout | guard de timeout ignora labels em estado terminal |
| **B** — reimpressão falha para sempre | processar `1002`, esperar >10 min (ou `Clock` falso), mandar novo grupo com `1002` | timeout reinicia quando muda o `groupId`; READY reaproveita a URL |
| **C** — erro persistente nunca dá timeout e derruba o grupo | grupo `1002 + 1005` | persistir o label antes de chamar o Kwai; falha por pedido, nunca pelo payload inteiro |
| **D** — re-upload a cada retry | grupo `1002 + 1001`; contar `upload/url` no hub | pular pedidos já READY no mesmo grupo |
| **E** — grupo lento trava a conta | fase 7.1 | key por `orderId` ou retry não-bloqueante (T3) |
| **F** — `createDelivery` duplicado por retry de transporte | `1008` | T5 + sem retry de transporte cego no create |
| **G** — pedido FAILED preso para sempre | `1003`, depois `POST /retry` | endpoint de retry manual que permite novo `createDelivery` de forma explícita |

**Pronto quando:** os 7 testes passam e o cenário da fase 6 roda com `1001..1011` juntos sem nenhum
pedido perdido, duplicado ou removido indevidamente.

### Fase 10 — Observabilidade (1 dia)

- [ ] Métricas Micrometer: `tag_poll_total{result}`, `tag_create_delivery_total`, `tag_timeout_total`,
      `tag_dlq_total`, histograma de tempo até READY.
- [ ] Logs JSON com MDC; um `correlationId` que atravessa hub → kwai-tag → Kafka → hub.
- [ ] Health check que fica DOWN se o consumer morrer.

**Quebrar:** derrubar o Kafka por 2 minutos. O que acontece com `packOrders`? E com o consumer quando
o Kafka volta? Os números das métricas batem com o que aconteceu?

---

## 6. Roteiro de caos (rodar ao final de cada fase a partir da 6)

| Ação | Comando | O que observar |
|---|---|---|
| Kwai lento | stub com `fixedDelayMilliseconds` | timeouts, circuit breaker |
| Kwai caindo conexão | `fault: CONNECTION_RESET_BY_PEER` | retry de transporte |
| Mensagem duplicada | publicar o mesmo payload 2x no Kafka UI | idempotência |
| App morto no meio | `docker kill` / `kill -9` | reprocessamento, estado no Mongo |
| Mongo fora | `docker stop mongo` | erro retryable? mensagem perdida? |
| Kafka fora | `docker stop kafka` | `packOrders` responde o quê? |
| Rebalance | subir 2ª instância do app | mensagens em voo, ordem |

---

## 7. Cronograma sugerido

| Semana | Fases |
|---|---|
| 1 | 0, 1, 2 |
| 2 | 3, 4, 5 |
| 3 | 6, 7 |
| 4 | 8 |
| 5 | 9, 10, revisão do `DIARIO.md` |

---

## 8. Critério final de sucesso

- [ ] O cenário com os 11 pedidos do WireMock roda ponta a ponta sem intervenção.
- [ ] `createDelivery` é chamado **exatamente uma vez** por pedido em todos os cenários, inclusive com
      duplicata, kill e retry de transporte (conferido no `/__admin/requests`).
- [ ] Nenhum pedido READY é removido do pacote no hub.
- [ ] Um grupo lento não atrasa outro grupo da mesma conta.
- [ ] Cada técnica T1–T10 tem um teste que falha sem ela.
- [ ] `DIARIO.md` explica, com as minhas palavras, cada bug A–G e por que a correção funciona.

---

## 9. Referências no projeto real (consultar depois de tentar)

| Assunto | Onde |
|---|---|
| Use case principal | `gubee-kwai-tag-domain/.../usecase/DownloadTagUseCase.kt` |
| Client e tradução de estados | `gubee-kwai-tag-api/.../DefaultTagClient.kt` |
| Janela de coleta | `gubee-kwai-tag-api/.../KwaiPickUpWindowResolver.kt` |
| Consumer, DLQ e notificação | `gubee-kwai-tag-main/.../listener/`, `.../config/parallel/` |
| Config de retry real | `gubee-kwai-tag-main/src/main/resources/application.yml` |
| Tabela `result→exceção` | `gubee-kwai-error/.../envelope/KwaiResultCode.kt` |
| Retry de transporte e circuit breaker | `gubee-kwai-error/.../GubeeErrorHandlerImpl.kt` |
| Lado do core (remoção de pedido) | `gubee-tag/gubee-tag-domain/.../PublishErrorPackageImpl.kt` |
| Retry e DLQ da lib | `gubee-libs/gubee-parallel-consumer/.../ParallelConsumerManager.java` |
| Doc oficial Kwai (raw) | `gubee-kwai/docs/research/_raw/order-and-logistics.txt` (linhas 697–1510) |
