# Galeria Android 0.9.28

Linha do tempo com zoom no ponto tocado e repetição de trechos, também nas fotos em movimento.

- Tocar na barra de reprodução abre a linha do tempo. Tocar nas miniaturas aproxima o ponto escolhido, até quadro a quadro.
- A visão geral mostra as duas pontas do vídeo. Duas alças brancas marcam o trecho a repetir; a área de fora escurece. Devolver as alças às pontas restaura a reprodução inteira.
- A faixa é contínua, com cantos arredondados, contorno, alças e cursor brancos, seguindo o editor.
- A foto em movimento tem a mesma barra, linha do tempo, zoom e alças. Um trecho marcado se repete; sem marcação, o clipe volta à foto quando termina.
- A marcação preserva o estado de pausa. Cancelar o gesto devolve as alças, e sair do visualizador encerra a sessão.
- O botão de pausa funciona também enquanto o próximo ciclo do trecho está carregando.
- O preparo antecipado das miniaturas aguarda o player ficar pronto, priorizando a abertura do vídeo.
- Acessibilidade: ações para iniciar ou terminar o trecho no cursor, restaurar o vídeo inteiro e aproximar a linha do tempo.
- Arrastar uma alça junto à borda da tela não aciona o gesto de voltar do Android.
- No Menu, a opção acompanha o dedo enquanto as outras abrem espaço. A ordem é mantida ao reabrir o app, e o efeito de toque fica no círculo do ícone.
- Na seleção, o botão de expandir fica no canto inferior esquerdo de cada miniatura, longe da barra de rolagem.

## APK

Versão 0.9.28, código Android 9028. O APK de distribuição usa a chave permanente das versões anteriores.

- Arquivo: `Galeria-Android-versao-0.9.28.apk`
- Tamanho: 45711139 bytes
- SHA-256: `b114b7e5e8f0482e95c8054c0709792ed988ac1efe64ad39384d4b190ceb6984`
- SHA-256 do certificado: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

## Validação

A execução completa `20261127-150559-866-c2082b40` (`-Delivery`) aprovou **390 testes unitários**, **200 testes de interface** e **54 contratos das ferramentas**, sem falhas ou ignorados. Lint sem erros (201 avisos). O recibo foi aprovado por `assert-delivery-validation.ps1`.

O APK otimizado passou pela verificação dos registradores de OCR preservados pelo R8. Os smokes de OCR nos fundos claro e escuro passaram em uma cópia assinada para testes, com as 621 entradas de conteúdo idênticas ao APK de distribuição. Essa cópia não é distribuída.

Pendente de conferência em aparelho real: vídeos longos da câmera, codecs de fabricantes e Motion Photos de diferentes marcas. A validação automatizada usa o emulador padrão e arquivos de teste.
