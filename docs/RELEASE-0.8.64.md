# Galeria Android 0.8.64

## Mudanças

- Painel de ocultos abre com os álbuns já disponíveis, sem esperar consultas ou recontagem. Novas entregas da lista principal entram no painel aberto; contagens são atualizadas em segundo plano.
- Revelação temporária consulta somente a pasta solicitada. Observadores de arquivos atualizam pastas reveladas e a pasta aberta, incluindo áreas `.nomedia`.
- Alterações pendentes retomam ao recuperar o foco ou voltar à tela. Mudanças ocorridas com o app fechado são detectadas pela geração do MediaStore.
- Abrir um álbum físico ou o destino após mover não depende de uma varredura geral. Catálogos válidos são reaproveitados; atualizações locais não declaram o catálogo inteiro reconciliado.
- Duração de vídeos é persistida por caminho, tamanho e modificação, evitando leitura repetida de arquivos inalterados. Não altera a qualidade nem a escolha das thumbnails.
- Manutenção reutiliza o trabalho realmente em andamento e não bloqueia a interface com consultas ao agendador. Varreduras antigas não sobrescrevem mutações mais recentes.

Privacidade, revelação temporária de 30 minutos limitada ao processo, seleção, ordenação, capas, edição, OCR, Motion Photos e cinema permanecem preservados. Nenhuma nova dependência ou migração destrutiva foi introduzida.

## Validação

A validação final, com versão `0.8.64` e código `8064` já definidos, passou em **227/227 unitários e 100/100 instrumentados** no AVD dedicado API 36, sem falhas/ignorados. Lint: zero erros e 126 avisos. Também passaram 45 contratos das ferramentas de validação, separados dos testes do aplicativo.

Execução local `20261005-105751-973-3d5b7be6`: 609,8 segundos incluindo a compilação release, sendo 422,1 segundos de instrumentação. Impressão digital dos inputs: `70D0D7891EECBEB0469474DF64C31DE8586ED65B75A0D4E0D78BD7E0CF6D5AD7`. Consulte também [o registro técnico da otimização e das tentativas anteriores](OTIMIZACAO-CATALOGO-2026-10-05.md).

APK release otimizado compilado e verificado: pacote `com.galeria.android`, versão `0.8.64`, código `8064`, ausência da Activity exclusiva de testes e assinatura válida com o mesmo certificado da 0.8.63.

- Arquivo: `Galeria-Android-versao-0.8.64.apk`
- Tamanho: 45.101.794 bytes
- SHA-256: `f63a2b874532a86cada48719b55d59afcbf964338404ee9aee2bccf591fba5d6`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Os testes automatizados de interface usam debug. Não substituem a confirmação do APK release otimizado em aparelhos físicos, codecs/trilhas e variantes de Motion Photos de fabricantes. Nenhuma chave ou credencial é adicionada ao Git e não há publicação na Play Store.

## Atualização

Atualize por cima da release anterior para preservar dados e preferências. Não desinstale uma instalação existente por conflito de assinatura sem preservar seus dados. Releases e APKs históricos permanecem intactos.
