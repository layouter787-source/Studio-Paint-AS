# Formato ASPK — intercâmbio Studio Paint AS ↔ Animation Studio

Extensão `.aspk`, MIME `application/vnd.animationstudio.project+zip`. É um ZIP com:

| Entrada | Conteúdo |
|---|---|
| `manifest.json` | metadados do projeto, frames, camadas, cels |
| `layers/<layerId>/raster/<drawingId>.png` | pintura raster do desenho (ARGB, tamanho = projeto) |
| `layers/<layerId>/strokes/<drawingId>.json` | traços vetoriais (modelo `BrushStroke` do Animation Studio) |
| qualquer outra entrada | preservada tal como está (áudio, câmara, …) |

## manifest.json

```json
{
  "format": "animation-studio-interchange", "version": 1, "producer": "Studio-Paint-AS 0.1.0",
  "name": "...", "width": 1920, "height": 1080, "fps": 12, "paperColorArgb": -1,
  "currentFrame": 0, "nextId": 12, "frameIds": [0, 1, 2],
  "layers": [{
    "id": 2, "name": "Camada 1", "kind": "DRAWING", "opacityPercent": 100,
    "visible": true, "blend": "NORMAL", "locked": false,
    "cels": { "0": 7, "1": 8 }, "drawings": [7, 8]
  }]
}
```

- `layers` estão ordenadas de baixo para cima.
- `cels` mapeia `frameId -> drawingId`; vários frames podem apontar para o mesmo desenho (exposição).
- `kind: SCENERY` = cenário partilhado por todos os frames (como no Animation Studio).
- `blend`: NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, ADD.

## Regras de compatibilidade

1. **Raster prevalece sobre vetor**: se um desenho tem PNG, os leitores ignoram os traços. O Studio Paint
   converte traços vetoriais em raster ao abrir e grava só raster no frame editado (perda do vetor *nesse* desenho).
2. **Round-trip não destrutivo**: ao guardar, só o frame escolhido é reescrito; os restantes frames, `fps`,
   entradas desconhecidas e ids são preservados.
3. IDs de camada e desenho são `Long`, únicos por projeto, alocados a partir de `nextId`.
4. Leitores devem rejeitar `version` maior que a suportada e caminhos com `..` ou absolutos.

## Do lado do Animation Studio (por fazer)

Para fechar o circuito falta, no repo `Animation-Studio-`: ler/escrever ASPK (a camada de domínio já tem
`Project/Layer/Drawing/BrushStroke`), registar o mesmo `intent-filter` (`VIEW`/`SEND` com o MIME acima),
e desenhar o raster PNG por cima/em vez dos traços. Do lado do Studio Paint já está feito: abrir `.aspk`, escolher o frame,
e "Enviar para o Animation Studio".
