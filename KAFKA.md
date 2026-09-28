# Kafka — tópicos e garantias do kwai-tag

> Guia de referência para montar producers, consumers e tópicos. Complementa a seção 4.4 do
> `PLANEJAMENTO.md` e o contrato do error-service (seção 4.6).
>
> Nomes **iguais ao projeto real**. Só o tópico ② não existe lá: é experimento da Fase 8.

---

## 1. Visão geral

São **4 tópicos**. A conversa com o Kwai e o `upload/url` para o hub são HTTP, não Kafka.

```
 hub-simulator ──HTTP packOrders──▶ kwai-tag
                                      │ produz
                                      ▼
                     ┌─────────────────────────────────┐
                     │ ① kwaitagservice.tag.download   │◀──────────────┐
                     └─────────────────────────────────┘               │ republica
                                      │ consome (kwai-tag)             │ depois do backoff
                     falha pendente   │                               │
                     (só Fase 8)      ▼                               │
                     ┌─────────────────────────────────┐               │
                     │ ② ...tag.download.retry         │               │
                     └─────────────────────────────────┘               │
                                      │ esgotou tentativas            │
                                      ▼                               │
                     ┌─────────────────────────────────┐               │
                     │ ③ errorservice.error.handler    │──▶ error-service-simulator
                     └─────────────────────────────────┘
                                      │ falha terminal de pedido
                                      ▼
                     ┌─────────────────────────────────┐
                     │ ④ tagservice.package.publisherror│──▶ hub-simulator
                     └─────────────────────────────────┘
```

| # | Tópico | Produz → consome | Para que serve | Key |
|---|---|---|---|---|
| ① | `kwaitagservice.tag.download` | kwai-tag → kwai-tag | Fila de trabalho: "busque/gere a etiqueta destes pedidos" | real: `accountId`. Proposta: `orderId` (decidido na Fase 7) |
| ② | `kwaitagservice.tag.download.retry` | kwai-tag → kwai-tag | Retry **sem travar a fila** (experimento) | mesma do ① |
| ③ | `errorservice.error.handler` | kwai-tag → error-service | DLQ: mensagens que esgotaram as tentativas | mesma do ① (preservada) |
| ④ | `tagservice.package.publisherror` | kwai-tag → hub | Avisar o seller que um pedido falhou | `sellerId` |

---

## 2. Não perder mensagem (durabilidade)

"Segurança" aqui é não perder mensagem. Autenticação no Kafka (SASL/ACL/TLS) fica fora do escopo local.

### Producer (vale para todos os tópicos)

| Config | Valor | Por quê |
|---|---|---|
| `acks` | `all` | o broker só confirma depois de gravar em todas as réplicas sincronizadas |
| `enable.idempotence` | `true` | se o producer re-tentar, o broker descarta a duplicata e **mantém a ordem** |
| `max.in.flight.requests.per.connection` | `≤ 5` | exigido pela idempotência para não reordenar |
| `retries` | alto (padrão `Integer.MAX_VALUE`) | com idempotência ligada, re-tentar é seguro |
| `delivery.timeout.ms` | padrão (2 min) | teto total do envio; depois disso é erro de verdade |

Regra: o `packOrders` só responde `202` **depois** que o Kafka confirmou o envio. Se o Kafka estiver
fora, responde erro e o hub re-tenta (ele tem retry 3×). Nunca responder 202 com envio "fire and forget".

### Consumer

| Config / regra | Valor | Por quê |
|---|---|---|
| `enable.auto.commit` | `false` | o commit é decisão do código, não do relógio |
| Momento do commit | **depois** de processar | *at-least-once*: se o app morrer no meio, a mensagem volta |
| `auto.offset.reset` | `earliest` local (o real usa `latest`) | localmente não perder o que foi publicado com o consumer parado |
| Envio para a DLQ | **síncrono** e **antes** do commit | o real faz `producer.send(...).get(10s)`; se a DLQ falhar, não commita |
| JSON inválido | DLQ, ou pular como no real (`skip-deserialization-errors`) | *poison pill* não pode travar a partição |

*At-most-once* (commitar antes de processar) perde mensagem. Você vai provar isso na Fase 5.

### Tópicos

| Config | Local | Produção (referência) | Por quê |
|---|---|---|---|
| `replication.factor` | 1 (um broker só) | 3 | cópias do dado |
| `min.insync.replicas` | 1 | 2 | com `acks=all`, garante 2 cópias antes de confirmar |
| `partitions` | 3 | 10 (20 no tópico de erro) | paralelismo; a ordem só vale **dentro** de uma partição |
| `retention.ms` | padrão (7 dias) | — | a DLQ precisa durar até alguém olhar |
| `cleanup.policy` | `delete` | `delete` | são eventos, não estado (nada de `compact`) |

---

## 3. Idempotência — a regra mais importante

**Kafka não entrega "exatamente uma vez" neste projeto.** O *exactly-once* do Kafka (transações) só
vale para ler do Kafka e escrever no Kafka. Aqui o processamento chama o Kwai, o hub e o Mongo, e isso o
Kafka não desfaz.

O que dá para ter:

> **at-least-once + processamento idempotente = efeito uma vez só.**

A mesma mensagem **vai** chegar duas vezes: kill, rebalance, retry, republicação do error-service,
duplicata do core. Cada efeito colateral precisa se proteger sozinho:

| Efeito | Risco se repetir | Proteção |
|---|---|---|
| `createDelivery` no Kwai | **duas entregas** (não idempotente) | gravar `deliveryRequestedAt` no `ORDER_DELIVERY_LABEL` **antes** de chamar ("intenção antes da ação"), com update condicional por `version`. Se já existe, não chama de novo |
| `deliveryDocumentV2` | nenhum (é leitura) | — |
| `upload/url` no hub | pacote duplicado | não reenviar pedido que já está `READY` no mesmo `groupId` |
| `publisherror` para o hub | hub remove a etiqueta / recalcula o grupo errado | publicar só na **transição** para `FAILED`/`TIMEOUT`, uma vez |
| Contador de tentativas no error-service | loop infinito se zerar | header `ERROR_CORRELATION_ID` preservado em todas as voltas |
| Mesmo `packOrders` duas vezes | duas mensagens no ① | o estado no Mongo por `accountId:orderId` absorve: o 2º processamento vê `READY`/`POLLING` e não refaz |

Em resumo: **o Mongo é a fonte de verdade; a mensagem é só um gatilho.** O consumer sempre lê o estado
antes de agir e grava o estado com lock otimista.

---

## 4. Ordem das mensagens

### Como o Kafka ordena

- A key passa por um hash que define a partição. A mesma key vai sempre para a mesma partição.
- **A ordem só é garantida dentro da partição.** Entre partições não há ordem.
- Mudar o número de partições depois faz as keys irem para outras partições, e a ordem antiga se perde.
  O número de partições se define na criação e não se mexe.

### Como o consumer preserva (Parallel Consumer)

| `processing-order` | Garante | Custo |
|---|---|---|
| `PARTITION` | ordem total da partição | uma mensagem lenta trava a partição inteira (várias contas) |
| `KEY` | ordem por key; keys diferentes em paralelo | uma mensagem em retry trava **só aquela key** |
| `UNORDERED` | nada | duas cópias do mesmo pedido podem rodar juntas → corrida no `createDelivery` |

### Por que a key importa tanto aqui

- **Key = `accountId` (real).** Um grupo com pedido em DOING, re-tentando por até 10 min, trava **todos
  os outros grupos daquele seller** (bug E, experimento 7.1).
- **Key = `orderId` (proposta).** Uma mensagem por pedido: um pedido lento só trava a si mesmo, e duas
  mensagens do mesmo pedido ficam serializadas, o que elimina a corrida no `createDelivery`. O custo é
  perder a "visão do grupo" na mensagem, que passa a ser reconstruída pelo `groupId` no Mongo.

### Onde a ordem quebra de propósito

- **Tópico ② e republicação pelo error-service:** a mensagem volta **para o fim da fila**, atrás de
  mensagens mais novas da mesma key. Aceitável **só** porque a proteção é o estado no Mongo, não a ordem.
- **Rebalance:** mensagens não commitadas são reprocessadas. Isso gera duplicata, não reordenação, e já
  é coberto pela idempotência (seção 3).

---

## 5. Cada tópico em detalhe

### ① `kwaitagservice.tag.download` — fila de trabalho

- **Payload:** `{orders:[{orderId}]}`. Com key `orderId`, vira um pedido só.
- **Headers:** `ACCOUNT_ID`, `TAG_GROUP_ID`, `TAG_TYPE`, `CORRELATION_ID`.
- **Consumer:** tentativas bloqueantes com backoff (1 s·2ⁿ, teto 60 s, 20×) e `processing-order: KEY`.
- **Regra de ouro:** um estado terminal de negócio (FAILED, TIMEOUT, sem endereço) **não lança
  exceção**. Ele grava o estado, publica no ④ e termina normalmente. Lançar exceção em estado terminal
  gera retry inútil e DLQ.

### ② `kwaitagservice.tag.download.retry` — retry sem bloqueio (Fase 8, técnica T3)

- Em vez de lançar exceção e travar a key, o consumer publica a mensagem aqui com um header
  `processAfter` e **commita a original**.
- Um consumer deste tópico espera até o `processAfter` e reprocessa.
- Ganho: um grupo lento não segura os outros. Custo: perde a ordem (seção 4) e aumenta o volume de
  mensagens.

### ③ `errorservice.error.handler` — DLQ

- **Headers:** os originais + `dead-letter-reason` (qual política se aplica), `dead-letter-topic`,
  `dead-letter-partition`, `dead-letter-offset`, `dead-letter-cause`, `kafka_dlt-original-partition`,
  `APP_ID` e `ERROR_CORRELATION_ID`.
- **Consumer (error-service):**
  - acumula as tentativas por `ERROR_CORRELATION_ID`;
  - republica no ① depois do backoff, na partição original;
  - ou marca `DEAD_ITEM`;
  - reprocessamento manual pela API.
- **Cuidado:** o retry do error-service **se soma** ao do consumer (experimento E1). A soma das duas
  camadas tem que caber no timeout de negócio.

### ④ `tagservice.package.publisherror` — aviso ao seller

- **Payload:** `{sellerId, groupId, failedOrders:[{orderId, errorMessageCode, errorMessageDescription}]}`,
  com key `sellerId`.
- **Consumer (hub):** remove o pedido dos pacotes existentes e recalcula o status do grupo.
- **Por que é perigoso:** o efeito é destrutivo. Uma mensagem publicada cedo demais (bug H) ou depois de
  um READY (bug A) apaga uma etiqueta válida. Só publica em **transição para estado terminal**, uma vez.

---

## 6. Checklist ao escrever o código

**Producer**
- [ ] `acks=all`, `enable.idempotence=true`, `max.in.flight ≤ 5`.
- [ ] Envio confirmado (`get()` / aguardar o future) antes de responder ao cliente HTTP.
- [ ] Key escolhida conscientemente (anotar no `DIARIO.md` por quê).
- [ ] Headers de correlação sempre presentes.

**Consumer**
- [ ] `enable.auto.commit=false`; commit só depois de processar ou de enviar à DLQ com sucesso.
- [ ] Estado lido do Mongo **antes** de qualquer efeito colateral.
- [ ] Estado terminal de negócio não lança exceção.
- [ ] Exceções não-retryable vão direto para a DLQ, e a lista bate com a política do error-service.
- [ ] Deserialização inválida não trava a partição.
- [ ] MDC preenchido e limpo no `finally`.

**Tópicos**
- [ ] Criados com 3 partições antes de subir os apps (não depender de auto-create).
- [ ] Nenhum tópico com número de partições alterado depois de ter dados.

---

## 7. Onde cada garantia é provada

| Garantia | Onde |
|---|---|
| at-least-once vs at-most-once | Fase 5 (kill com commit antes e depois) |
| producer idempotente e `acks=all` | Fase 5 (`packOrders` com Kafka fora) |
| efeito uma vez só com mensagem duplicada | Fase 8, T5 (`1011`, `1008`, duplicata manual) |
| ordem por key e bloqueio por key | Fase 7 (7.1 a 7.6) |
| retry sem bloqueio e o custo na ordem | Fase 8, T3 |
| DLQ e retry em duas camadas | Fase 8B (E1–E9) |
| aviso terminal único ao hub | Fase 9 (bugs A e H) |
