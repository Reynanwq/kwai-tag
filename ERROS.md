# Catálogo de erros — kwai-tag

> Todo erro que o fluxo de etiqueta pode encontrar, com **o que o projeto real faz hoje** e o que eu
> decidi fazer aqui. Preencher a coluna **Decisão** antes de codar a Fase 8 — cada linha vira um teste.
>
> Levantado a partir de: `KwaiResultCode.kt`, `DefaultTagClient.kt`, `DownloadTagUseCase.kt`,
> `application.yml` e `ErrorConfiguration.kt` do `gubee-kwai-tag`, `GubeeErrorHandlerImpl.kt` e
> o `gubee-error-handler`.

---

## Como ler

Um erro passa por até **4 camadas**. Cada coluna diz o que acontece em cada uma:

1. **HTTP** — `GubeeErrorHandler`: retry de transporte (3×, backoff 1,5) e circuit breaker por
   `conta + operação`. Renovação de token (3×) em erro de autenticação.
2. **Use case** — trata estado de negócio **por pedido** (publica falha para o seller ou marca pendente).
3. **Consumer** — parallel-consumer: até 20 tentativas (1 s · 2ⁿ, teto 60 s). Exceções da lista
   `non-retryable-exceptions` vão direto para a DLQ.
4. **Error service** — consome `errorservice.error.handler`, aplica a política da exceção, republica
   no tópico de origem ou marca `DEAD_ITEM`.

Categorias (as mesmas do `TagCategory` do real): `BUSINESS`, `UNAVAILABLE_API_SERVICE`,
`TOO_MANY_REQUEST`, `BAD_REQUEST`, `NOT_FOUND`, `AUTHORIZATION`, `AUTHENTICATION`, `CONCURRENCY_DB`,
`CONCURRENCY_API`, `UNDEFINED`.

---

## 1. Estados do `deliveryDocumentV2` (não são erro, são fluxo)

| Código | Significado | Exceção (real) | Use case (real) | Consumer (real) | Error service (real) | Cenário WireMock | Decisão |
|---|---|---|---|---|---|---|---|
| `200` / `1` + URL | Etiqueta pronta | — | upload no hub, label `READY` | — | — | `1002`, `1007` | |
| `200` sem URL | Sucesso incompleto | `DeliveryDocumentPendingException` | trata como pendente | retry | 5 s→60 s, 20× | `1010` | |
| `11011` DOING | Ainda processando | `DeliveryDocumentPendingException` | label `POLLING`, relança no fim do grupo | retry (20×) | 5 s→60 s, 20× (tick real ≥ 60 s) | `1001`, `1004` | |
| `11012` FAILED | Kwai desistiu da entrega | `DeliveryDocumentFailedException` | label `FAILED`, publica falha, **não relança** | — | — | `1003` | |
| `11013` NOT_FOUND | Não há entrega | `DeliveryDocumentNotFoundException` | chama `createDelivery`, label `POLLING` | — | — | `1001`, `1011` | |
| `11014` ERROR | Erro genérico ambíguo | `DeliveryDocumentErrorException` | **não captura** → derruba o grupo inteiro | retry (20×) | 5 s→30 s, 3× → até 80 chamadas | `1005` | |

## 2. Erros transversais do envelope Kwai (qualquer endpoint)

| Código | Significado | Exceção (real) | Categoria | Consumer (real) | Error service (real) | Cenário WireMock | Decisão |
|---|---|---|---|---|---|---|---|
| `440` / `441` | app key / secret inválido | `AuthenticationException` | AUTHENTICATION | retry | 10 min→1 h, 3× | stub com `result: 440` | |
| `442` | timestamp fora da janela | `AuthenticationException` | AUTHENTICATION | retry (token renovado 3× antes) | 10 min→1 h, 3× | `result: 442` | |
| `443` | assinatura inválida (bug nosso) | `BadRequestException` | BAD_REQUEST | **não-retryable** → DLQ | nenhuma tentativa → `DEAD_ITEM` | request sem `sign` | |
| `445` / `446` | merchant inválido / sem permissão | `AuthorizationException` | AUTHORIZATION | retry | 10 min→1 h, 3× | `result: 446` | |
| `447` | versão inválida | `BadRequestException` | BAD_REQUEST | não-retryable | `DEAD_ITEM` | `result: 447` | |
| `6001` | body vazio | `UnprocessableEntityException` | BUSINESS | **não-retryable** → DLQ | cai em `KwaiException`: 15 min, **5×** (inconsistente) | `deliveryInfo` exigindo body | |
| `4999` | erro interno Kwai | `UnavailableServiceException` | UNAVAILABLE_API_SERVICE | retry (+3× no HTTP) | cai em `KwaiException`: 15 min, 5× | `result: 4999` | |
| `5000` | service busy (rate limit) | `UnavailableServiceException` | deveria ser TOO_MANY_REQUEST | retry (+3× no HTTP) | cai em `KwaiException`: 15 min, 5×. **Sem slow-down**: o rate limiter configurado nunca age | `1006` | |
| outro | desconhecido | `UnexpectedException` | UNDEFINED | retry | 15 min, 5× | `result: 9999` | |

## 3. Erros de regra de negócio do TAG

| Situação | Exceção (real) | Use case (real) | Consumer / Error service (real) | Cenário | Decisão |
|---|---|---|---|---|---|
| Merchant sem endereço de envio (`type=1`) | `DeliveryOptionsUnavailableException` | label `FAILED`, publica falha | não chega lá (tratado no use case) | `deliveryInfo` sem `type=1` | |
| Só `PICK_UP` disponível, sem janela ou endereço de coleta | `DeliveryOptionsUnavailableException` | idem | idem | `deliveryInfo` só com `[1]` e sem `pickUpTimeList` | |
| `orderId` não numérico | `UnprocessableEntityException` | **não captura** → derruba o grupo | DLQ direto; error service re-tenta 5× (inconsistente) | pedido `"abc"` | |
| Timeout total do polling (> 10 min) | — | label `TIMEOUT`, publica falha, não relança | — | `1004` | |
| Timeout total atingido por pedido já `READY` | — | **bug A**: vira `TIMEOUT`, hub remove a etiqueta | — | `1002 + 1004` | |
| Reimpressão depois de 10 min | — | **bug B**: `TIMEOUT` imediato | — | `1002` duas vezes | |

## 4. Erros de infraestrutura

| Situação | Exceção (real) | HTTP / Consumer (real) | Error service (real) | Como provocar | Decisão |
|---|---|---|---|---|---|
| Kwai lento (read timeout) | `SocketTimeoutException` | retry de transporte 3× — **no `createDelivery` pode duplicar entrega** | classificado pela causa raiz → `Exception`: 15 min, 6× | `1008` | |
| Conexão caindo | `ProcessingException` | retry de transporte 3× | idem | `1009` | |
| Circuit breaker aberto | `CallNotPermittedException` | falha rápida | `Exception`: 15 min, 6× | 20 falhas seguidas de `1009` | |
| Mongo fora | `MongoException` | retry do consumer | `Exception`: 15 min, 6× | `docker stop mongo` | |
| Conflito de versão no Mongo | `OptimisticLockException` | retry | fixo 1 min, 20× | 2 consumers no mesmo pedido | |
| Hub (`upload/url`) fora | `WebApplicationException` / `ProcessingException` | retry do consumer; o pedido fica pendente | `Exception`: 15 min, 6× | parar o hub-simulator | |
| JSON inválido no tópico | `DeserializationException` | `skip-deserialization-errors`: pulado sem DLQ | — | publicar lixo no Kafka UI | |
| Headers obrigatórios ausentes | `BadRequestException` | não-retryable → DLQ | `DEAD_ITEM` | publicar sem `TAG_GROUP_ID` | |
| Exceção sem política no error service | qualquer | DLQ | **"config not found": só log, mensagem perdida** | exceção nova sem linha na política | |

---

## 5. Perguntas que a coluna Decisão precisa responder

1. **Quem é dono do retry** de cada erro: o consumer (rápido, bloqueia a chave) ou o error service
   (lento, não bloqueia)? As duas camadas somadas não podem passar do timeout de negócio.
2. **Quando o seller é avisado**: só quando o erro é terminal. Nunca a cada ida para a DLQ (bug H).
3. **O erro para o pedido ou o grupo?** Tudo que é de um pedido deve ser tratado por pedido.
4. **Retry é seguro?** Nada que chama `createDelivery` pode ser re-tentado sem a guarda de
   idempotência (`deliveryRequestedAt`).
5. **Classificar pela exceção de domínio ou pela causa raiz?** (experimento E6)
6. **Existe linha de fallback** na política para qualquer exceção não prevista?
