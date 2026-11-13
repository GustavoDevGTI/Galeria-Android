# Galeria Android 0.9.9

- **Segurar para 2x:** só segurar o dedo num vídeo vertical acelera para 2x enquanto o dedo está na tela, e soltar volta à velocidade anterior. Segurar e puxar para baixo cerca de um dedo de distância vibra e fixa o 2x: ao soltar, ele continua como a velocidade escolhida, a mesma mostrada no rótulo do canto inferior direito. Para voltar a 1x, toque no rótulo de velocidade.

## APK

- Arquivo Galeria-Android-versao-0.9.9.apk
- Pacote com.galeria.android
- Versão 0.9.9, código 9009
- Tamanho 45208442 bytes
- SHA-256 do arquivo a249ed3c6a5439550f7660aa8bfc7280eb10c4b37b63210dc8d43f3d7f21c736
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

**Nenhum teste em emulador foi executado nesta versão**, por decisão do responsável pelo projeto; a conferência é feita em aparelho real. Não há recibo de entrega para a 0.9.9 e a regressão completa (`-Delivery`) não rodou.

O que rodou:

- Execução 20261113-114406-862-a6e2d15a, sem emulador: 54 contratos das ferramentas e **270 testes unitários**, sem falhas ou ignorados.
- Lint sem erros, com 150 avisos, e montagem release otimizada. A montagem preservou os três registradores do OCR e seus construtores.

Os testes novos cobrem a diferença entre só segurar e segurar puxando para baixo, e a distância mínima para fixar o 2x.

Pendente de conferência em aparelho real: segurar, puxar para baixo, soltar e conferir que o rótulo continua em 2x.
