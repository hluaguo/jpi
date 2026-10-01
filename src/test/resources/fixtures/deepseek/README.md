Recorded wire fixtures. Source: dev.jpi.ai.providers.RecordDeepSeekFixtures
(dev-time only; never run by the build). Provider: DeepSeek API, model deepseek-flash,
OpenAI chat-completions wire format, streamed via POST /v1/chat/completions.
Each <name>.request.json is the exact body OpenAICompletionsProvider.buildRequest produced;
each <name>.response.sse is the verbatim response body. chat-error-auth.response.txt holds a
non-200 status line + body. No credentials are recorded.
