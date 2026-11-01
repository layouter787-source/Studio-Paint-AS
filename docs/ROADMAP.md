# Roadmap

## Feito (v0.1)
- Projetos locais com gravação atómica + cópia de segurança, autosave, miniaturas, duplicar/apagar.
- Editor: zoom/pan/rotação com dois dedos, desenho com pressão de stylus, barras flutuantes móveis e ocultáveis.
- Pincéis (motor de dabs em Kotlin): lápis, tinta, marcador, aerógrafo, carvão, aguarela suave; borracha.
- Camadas: visibilidade, bloqueio, opacidade, 7 modos de mistura, duplicar, reordenar, fundir, desfazer/refazer.
- Balde (tolerância + expansão), conta-gotas, seletor de cor HSV/hex com recentes.
- Exportar PNG; formato ASPK de ida e volta (frames, cenários, extras preservados); abrir/enviar `.aspk`.
- libmypaint nativo (experimental, `-PenableLibMyPaint=true`) com ponte JNI.

## Próximo
1. CI no GitHub Actions (workflow `docs/android-ci.yml.txt` por copiar para `.github/workflows/`).
2. Validar o build nativo do libmypaint e fixar o SHA256 da release.
3. Desempenho: renderização por tiles/GPU (hoje o bitmap inteiro é redesenhado a cada evento).
4. Pincel borrão no motor Kotlin, importação de pincéis `.myb` (json-c), texturas de grão.
5. Seleção/transformação, máscaras de recorte, bloqueio de alfa.
6. Guias de desenho: régua, simetria, perspetiva; estabilização avançada.
7. Exportar PSD/JPG/WebP; paletas.
8. No Animation Studio: suporte ASPK completo (ver `docs/INTERCHANGE.md`).
9. Testes instrumentados e de desempenho em dispositivos reais.
