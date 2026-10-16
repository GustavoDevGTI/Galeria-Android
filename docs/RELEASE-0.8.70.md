# Galeria Android 0.8.70

## Correções

- Corrigida a inicialização do OCR no APK release otimizado. O R8 removia construtores de registradores ML Kit carregados por reflexão; o reconhecimento falhava antes de analisar a imagem. As regras agora preservam esses pontos de entrada sem desabilitar a otimização do aplicativo.
- O toque prolongado e a opção Reconhecer texto mantêm a seleção diretamente sobre a foto e permitem copiar o conteúdo reconhecido.
- O indicador circular de reconhecimento ganhou uma superfície discreta de contraste, visível sobre imagens claras ou escuras, tanto no visualizador quanto no editor. Não foram acrescentadas frases visíveis de carregamento.
- A montagem da release verifica automaticamente os três registradores e seus construtores no resultado do R8. A ausência de um componente bloqueia a build.
- Incluído um smoke separado, baseado somente em APIs Android, para testar o OCR no código otimizado. Ele não depende do runner AndroidX debug nem de nomes internos alterados pelo R8.

Nenhuma função removida, nova dependência ou alteração no banco. O motor local de OCR, a organização das mídias e as demais ações do visualizador foram mantidos.

## Validação

Com versão `0.8.70` e código `8070` definidos, a execução completa `20261016-102618-767-6beb703b` aprovou **237/237 unitários e 125/125 instrumentados**, sem falhas ou ignorados, no emulador dedicado Android 16/API 36. Lint: zero erros e 129 avisos. Os 45 contratos das ferramentas de validação também passaram.

A execução durou 660,2 segundos, incluindo 580,3 segundos de instrumentação. Foram exercitados os recursos anteriores, incluindo cinema/trilhas, reprodução, navegação, edição, timeline, seleção, catálogo e álbuns ocultos. Evidências locais em `app/build/reports/validation/20261016-102618-767-6beb703b/` e `delivery.json`; o portão confere os fontes, os APKs e os relatórios aprovados.

O smoke do código otimizado da 0.8.70 passou em **10,6 segundos**: abre uma imagem própria, reconhece por toque prolongado, seleciona e copia o texto, confere o clipboard e confirma que a imagem não foi modificada. A imagem real que reproduzia a falha também foi usada na validação da correção, por toque prolongado e pelo submenu após reabrir o app; ela não integra o repositório.

Para preservar os dados da instalação debug existente no emulador, o smoke usou uma cópia com assinatura de teste. Os **28 arquivos DEX, bibliotecas nativas e assets** conferidos são idênticos aos do APK original otimizado. Somente o APK de assinatura permanente abaixo é distribuído; a cópia diagnóstica não é anexada à release. Evidências: `app/build/reports/release-0.8.70-ocr/`.

A proteção estrutural também foi testada com o resultado defeituoso anterior e o rejeitou. Isso complementa, mas não substitui, a execução real do OCR na release. Comandos e limites no [guia de validação](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.70/docs/TESTES-VALIDACAO.md).

## APK

- Arquivo: `Galeria-Android-versao-0.8.70.apk`
- Pacote: `com.galeria.android`
- Versão: `0.8.70` / código `8070`
- Tamanho: 45.124.219 bytes
- SHA-256: `23fb1cfbb5fa9bf00bca625fe1df89de04ccd5955fbce1cd7534a2229fc4e30f`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Conferidos assinatura válida, certificado permanente das versões anteriores, versão/pacote, ausência de modo depurável e ausência dos componentes exclusivos de testes. Os relatórios e o mapeamento R8 foram preservados localmente em `app/build/reports/release-0.8.70-*`.

## Atualização e limites

Atualize por cima da release anterior para preservar mídias, preferências e organização. Não é necessário reinstalar do zero. Nenhuma chave ou credencial é incluída no Git; o APK é anexado somente ao GitHub Releases, sem publicação na Play Store. As versões e os APKs históricos permanecem intactos.

O teste no emulador não comprova todos os codecs, variantes de Motion Photos ou comportamentos de fabricantes. O OCR agora inicializa corretamente na release, mas sua precisão continua dependendo da imagem e do texto; não há garantia de leitura perfeita de qualquer fotografia.
