# AI connection examples (not FOE model recommendations)

Checked against provider documentation on 2026-09-21. These entries establish only that the named API interface and model ID are documented; they do not measure Jianyu/FOE opportunity quality, safety, costs for a particular account, or availability in every region. Recheck the provider's current documentation before changing a preset or publishing a real compatibility rating.

| Provider | Example model ID | Chat-completions documentation | Terms and data handling |
|---|---|---|---|
| OpenAI | `gpt-4.1-mini` | [Official model page](https://developers.openai.com/api/docs/models/gpt-4.1-mini) | [Services agreement](https://openai.com/policies/services-agreement/), [business data information](https://openai.com/business-data/) |
| DeepSeek | `deepseek-flash` | [Official chat-completions API](https://api-docs.deepseek.com/api/create-chat-completion/) | [Open Platform terms](https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html), [privacy policy](https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html) |

The native app's optional public-sample probe is a single connection and task-shape check, not a benchmark. The only existing compatibility manifest, `compatibility.demo.json`, contains synthetic demonstration entries and no real-model rating. A real recommendation must be backed by a versioned, reproducible multi-case benchmark covering structure, constraints, diversity, Nothing, child labeling, provenance, cost, latency, and context capacity.
