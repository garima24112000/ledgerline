{{/*
Names are "<release>-<component>", e.g. ledgerline-gateway-api, ledgerline-postgres.
One release per namespace, so no extra suffixes are needed.
*/}}
{{- define "ledgerline.name" -}}
{{- printf "%s-%s" .root.Release.Name .component | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/* Labels on every object. */}}
{{- define "ledgerline.labels" -}}
{{ include "ledgerline.selectorLabels" . }}
app.kubernetes.io/part-of: ledgerline
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end }}

{{/* Labels a Service/StatefulSet/Deployment selects pods by. Never change these on a live release. */}}
{{- define "ledgerline.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{/* Secret holding DB_PASSWORD, ADMIN_PASSWORD, INTERNAL_SERVICE_TOKEN. */}}
{{- define "ledgerline.secretName" -}}
{{- .Values.secrets.existingSecret | default (printf "%s-secrets" .Release.Name) -}}
{{- end }}

{{/* Connection settings: the in-cluster service when enabled, otherwise the managed endpoint. */}}
{{- define "ledgerline.databaseUrl" -}}
{{- if .Values.postgres.enabled -}}
jdbc:postgresql://{{ .Release.Name }}-postgres:5432/{{ .Values.postgres.database }}
{{- else -}}
{{- required "external.databaseUrl is required when postgres.enabled=false" .Values.external.databaseUrl -}}
{{- end -}}
{{- end }}

{{- define "ledgerline.redisHost" -}}
{{- if .Values.redis.enabled -}}
{{ .Release.Name }}-redis
{{- else -}}
{{- required "external.redisHost is required when redis.enabled=false" .Values.external.redisHost -}}
{{- end -}}
{{- end }}

{{- define "ledgerline.kafkaBootstrapServers" -}}
{{- if .Values.kafka.enabled -}}
{{ .Release.Name }}-kafka:9092
{{- else -}}
{{- required "external.kafkaBootstrapServers is required when kafka.enabled=false" .Values.external.kafkaBootstrapServers -}}
{{- end -}}
{{- end }}
