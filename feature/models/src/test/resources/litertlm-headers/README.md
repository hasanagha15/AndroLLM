# litertlm-headers test fixtures

Used by `PinnedModelFamilyResolutionTest`.

- Each `<catalog id>.header.bin` file is the beginning of the pinned `.litertlm` file named in `index.json` (repo revision, file name, sha256). It holds the container header and the `LlmMetadataProto` section byte-for-byte: model type, chat template and stop tokens.
- All other section entries have their offsets zeroed, so the engine's public `ContainerMetadataReader.parse` reads the fixture without the multi-GB weights. No model weights are included.
- `llmMetadataSha256` lets the test verify that the metadata section is untouched.
- Regenerate with `python -I core/models/tools/make_litertlm_metadata_fixture.py <this dir> <catalog ids...>`.

Licences of the source models: Qwen3-4B / Qwen3-4B-Instruct-2507 / Qwen3.5-2B / Qwen3.5-4B and Ministral-3-3B-Instruct-2512 are Apache-2.0; Falcon-H1-3B-Instruct is under the Falcon LLM License (TII).
