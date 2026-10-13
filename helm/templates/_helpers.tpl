{{- define "gateway.fullname" -}}
{{- /*
Deliberately NOT the standard Helm "release-chart" fullname convention.
Every service's ConfigMap bakes in plain cross-service DNS names
(e.g. KAFKA_BOOTSTRAP_SERVERS: kafka:9092, or another service's own URI pointing at this one by name)
— release-prefixing this Service name would break every one of those the
moment the umbrella chart installs with any release name other than
"gateway". Keep it the chart name, full stop.
*/ -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "gateway.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: Helm
{{- end -}}

{{- define "gateway.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "gateway.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- .Values.serviceAccount.name | default (include "gateway.fullname" .) -}}
{{- else -}}
{{- .Values.serviceAccount.name | default "default" -}}
{{- end -}}
{{- end -}}
