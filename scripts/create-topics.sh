#!/bin/sh
# Cria os tópicos do kwai-tag. Roda dentro do container kafka-init, depois que o broker está saudável.
# Idempotente: --if-not-exists permite rodar quantas vezes quiser.
#
# ATENÇÃO (Windows): este arquivo precisa ter fim de linha LF, não CRLF.
# Com CRLF o sh do container falha com erros como "not found" ou "\r: command not found".

set -e

BOOTSTRAP="${BOOTSTRAP_SERVER:-kafka:29092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

TOPICS="
kwaitagservice.tag.download
kwaitagservice.tag.download.retry
errorservice.error.handler
tagservice.package.publisherror
"

for topic in $TOPICS; do
  echo "Criando topico: $topic (particoes=$PARTITIONS)"
  "$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor 1
done

echo ""
echo "Topicos existentes:"
"$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" --describe --exclude-internal
