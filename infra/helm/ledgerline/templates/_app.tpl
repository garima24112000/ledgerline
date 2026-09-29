{{/*
One app = Deployment + Service (+ ServiceMonitor). Called from gateway-api.yaml etc. as
  include "ledgerline.app" (dict "root" $ "component" "gateway-api" "app" .Values.gatewayApi)
*/}}
{{- define "ledgerline.app" -}}
{{- $root := .root -}}
{{- $app := .app -}}
{{- $name := include "ledgerline.name" . -}}
{{- /* host:port of each in-cluster dependency to wait for; managed services are skipped */ -}}
{{- $waitFor := list -}}
{{- range $app.waitFor -}}
{{- if and (eq . "postgres") $root.Values.postgres.enabled -}}
{{- $waitFor = append $waitFor (printf "%s-postgres:5432" $root.Release.Name) -}}
{{- else if and (eq . "redis") $root.Values.redis.enabled -}}
{{- $waitFor = append $waitFor (printf "%s-redis:6379" $root.Release.Name) -}}
{{- else if and (eq . "kafka") $root.Values.kafka.enabled -}}
{{- $waitFor = append $waitFor (printf "%s-kafka:9092" $root.Release.Name) -}}
{{- else if eq . "gateway-api" -}}
{{- $waitFor = append $waitFor (printf "%s-gateway-api:%v" $root.Release.Name $root.Values.gatewayApi.port) -}}
{{- end -}}
{{- end -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "ledgerline.labels" . | nindent 4 }}
spec:
  {{- if not (and $app.hpa $app.hpa.enabled) }}
  replicas: {{ $app.replicas }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "ledgerline.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "ledgerline.labels" . | nindent 8 }}
      annotations:
        # Roll the pods when shared config or secrets change (env vars are only read at start).
        checksum/config: {{ include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum }}
        checksum/secret: {{ include (print $root.Template.BasePath "/secret.yaml") $root | sha256sum }}
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        seccompProfile:
          type: RuntimeDefault
      # Spread replicas over the worker nodes when possible (soft: never blocks scheduling).
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              {{- include "ledgerline.selectorLabels" . | nindent 14 }}
      {{- if $waitFor }}
      initContainers:
        # Waits until each dependency accepts TCP connections, so a fresh install doesn't
        # crash-loop the app while Postgres/Kafka are still starting.
        - name: wait-for-dependencies
          image: busybox:1.36
          command:
            - sh
            - -c
            - |
              for target in {{ join " " $waitFor }}; do
                until nc -z -w 2 "${target%:*}" "${target#*:}"; do echo "waiting for $target"; sleep 2; done
              done
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: { drop: [ALL] }
      {{- end }}
      containers:
        - name: {{ .component }}
          image: "{{ $root.Values.image.repository }}/{{ .component }}:{{ $root.Values.image.tag }}"
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ $app.port }}
          envFrom:
            - configMapRef:
                name: {{ $root.Release.Name }}-config
            - secretRef:
                name: {{ include "ledgerline.secretName" $root }}
          {{- with $app.env }}
          env:
            {{- range $key, $value := . }}
            - name: {{ $key }}
              value: {{ $value | quote }}
            {{- end }}
          {{- end }}
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: {{ $root.Values.probes.startup.periodSeconds }}
            failureThreshold: {{ $root.Values.probes.startup.failureThreshold }}
            timeoutSeconds: {{ $root.Values.probes.startup.timeoutSeconds }}
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: {{ $root.Values.probes.liveness.periodSeconds }}
            failureThreshold: {{ $root.Values.probes.liveness.failureThreshold }}
            timeoutSeconds: {{ $root.Values.probes.liveness.timeoutSeconds }}
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            periodSeconds: {{ $root.Values.probes.readiness.periodSeconds }}
            failureThreshold: {{ $root.Values.probes.readiness.failureThreshold }}
            timeoutSeconds: {{ $root.Values.probes.readiness.timeoutSeconds }}
          resources:
            {{- toYaml $app.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: [ALL] }
          volumeMounts:
            - name: tmp # Tomcat and the JVM need a writable /tmp; the rest of the filesystem is read-only
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    {{- include "ledgerline.labels" . | nindent 4 }}
spec:
  selector:
    {{- include "ledgerline.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ $app.port }}
      targetPort: http
{{- if $root.Values.serviceMonitor.enabled }}
---
# Tells the Prometheus Operator to scrape every pod behind this Service.
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ $name }}
  labels:
    {{- include "ledgerline.labels" . | nindent 4 }}
spec:
  selector:
    matchLabels:
      {{- include "ledgerline.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: {{ $root.Values.serviceMonitor.interval }}
{{- end }}
{{- end }}
