# Studio Paint AS

App Android de desenho profissional (pintura raster), irmão do **Animation Studio**
(`layouter787-source/Animation-Studio-`). Os dois apps trocam projetos pelo formato aberto
**ASPK** (`.aspk`) — ver [`docs/INTERCHANGE.md`](docs/INTERCHANGE.md).

> Estado honesto: v0.1 é uma fundação real, mas foi escrita sem acesso a um build local, por isso
> pode ter erros de compilação por corrigir. Nada é dado como feito sem estar implementado —
> ver [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Compilar

```bash
gradle testDebugUnitTest assembleDebug                 # motor Kotlin
gradle assembleDebug -PenableLibMyPaint=true           # + libmypaint nativo (precisa de NDK + CMake)
```

O APK sai em `app/build/outputs/apk/debug/`. Para CI, copie `docs/android-ci.yml.txt` para
`.github/workflows/android.yml`.

## Arquitetura

| Pacote | Papel |
|---|---|
| `domain` | `ViewTransform` (zoom/pan/rotação) |
| `format` | leitura/escrita ASPK (ZIP + JSON + PNG) |
| `engine` | documento/camadas/histórico, pincéis, preenchimento, composição, ponte ASPK |
| `storage` | projetos locais (gravação atómica) |
| `ui` | ecrã inicial, editor, barras flutuantes, painéis |
| `cpp` | ponte JNI do libmypaint |

## Integração com o Animation Studio

Abrir `.aspk` (inclusive vindo de outro app por `VIEW`/`SEND`), escolher o frame, desenhar, e
"Enviar para o Animation Studio". Guardar reescreve só esse frame e preserva o resto do projeto.
