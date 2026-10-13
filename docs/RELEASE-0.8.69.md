# Galeria Android 0.8.69

## Mudanças

- Barra inferior na página inicial e dentro dos álbuns, com os quatro atalhos escolhidos: Ordenar, Filtrar, Ocultos e Configurações. Distribuição uniforme, ícones discretos e cores do tema.
- Ao selecionar itens, a barra dá lugar às ações de seleção existentes; ao sair da seleção, ela retorna. O visualizador de mídia mantém sua própria barra.
- Menus superiores simplificados por redistribuição de atalhos, sem remoção intencional de funcionalidades. Organização/visualização, criar pasta, aleatório, capas, espaçamento e cinema continuam disponíveis conforme a tela/coleção.
- Ocultos acessível dentro do álbum reutiliza o painel existente; fechar retorna ao mesmo álbum, preservando a sessão de revelação temporária. Configurações também retorna ao álbum, com o tema reaplicado quando alterado.
- Excluir ocupa uma área fixa à direita na barra inferior do visualizador de fotos e vídeos. A posição não muda ao aparecerem OCR ou Motion Photo; confirmação e regras de Lixeira permanecem.
- Velocidade do vídeo por toque curto: 1× → 1,5× → 2× → 1×. Segurar abre o seletor existente, incluindo 0,5×, sem executar o ciclo. A velocidade é aplicada ao mesmo player, sem iniciar um vídeo pausado.
- Corrigido o fechamento do indicador de carregamento quando o Android já removeu sua janela: uma conclusão assíncrona não deve encerrar o app nem deixar o pedido bloqueado nesse caso.

Nenhuma nova dependência ou alteração no banco. As funcionalidades não foram intencionalmente removidas; houve mudança explícita de acesso aos atalhos conforme solicitado. Detalhes em [barra inferior](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.69/docs/BARRA-INFERIOR-2026-10-13.md) e [velocidade do vídeo](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.69/docs/VELOCIDADE-VIDEO-2026-10-13.md).

## Validação

Com versão `0.8.69` e código `8069` definidos, a regressão completa aprovou **237/237 testes unitários e 124/124 testes instrumentados**, sem falhas ou ignorados, no emulador dedicado Android 16/API 36 com SwiftShader. Lint: zero erros e 129 avisos; 45 contratos das ferramentas de teste também aprovados.

Execução final `20261013-155743-916-b246ef9d`: 676,1 segundos no total, sendo 521,6 segundos de instrumentação. Evidências locais: `app/build/reports/validation/20261013-155743-916-b246ef9d/result.json` e `app/build/reports/validation/delivery.json`. O portão de entrega verifica fontes e hashes dos APKs/relatórios; nenhuma tentativa parcial, falha ou incompleta foi promovida a aprovação.

A build release otimizada e assinada passou. Conferidos pacote `com.galeria.android`, versão `0.8.69`, código `8069`, manifesto sem Activity/runner exclusivos dos testes e sem modo depurável, além da assinatura válida com o mesmo certificado permanente da 0.8.68.

- Arquivo: `Galeria-Android-versao-0.8.69.apk`
- Tamanho: 45.124.188 bytes
- SHA-256: `038a8699a11354bb38d79576615990fab23f22523e220ad2749a7831100ae425`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Evidências locais da build e das verificações: `app/build/reports/release-0.8.69-{build,signature,package,manifest}.txt`.

A tentativa completa `20261013-154933-639-a3a3837c` foi rejeitada: a instrumentação parou incompleta, com falha de foco e crash ao dispensar uma janela já removida. O diagnóstico registrou uma janela ANR do SystemUI em primeiro plano (`release-0.8.69-failed-device-window.txt`) e o stacktrace do crash (`release-0.8.69-failed-crash.txt`). O emulador foi reiniciado sem apagar dados. Além da recuperação do ambiente, `ActivityLoadingIndicator.finish` foi corrigido para liberar o estado ocupado antes de dispensar a janela e tratar especificamente a remoção já realizada pelo WindowManager. Um novo teste remove a janela de fato, exige fechamento idempotente e permite uma nova operação. Não foram removidas as verificações de feedback nem de bloqueio de duplicação. A primeira build release anterior a essa correção é histórica e não aprova o APK final; será recompilada e verificada novamente.

As duas primeiras execuções focadas (`release-0.8.69-loading-fix/`) falharam no novo teste antes de simular a remoção: o teste consultava `isAttachedToWindow` no mesmo bloco principal de `show`, antes da primeira passagem de layout. A fixture passou a exigir que o indicador esteja visível antes de removê-lo. A implementação também foi revisada para dispensar normalmente uma janela recém-adicionada, mesmo antes de ser desenhada, tratando apenas a exceção específica de remoção já realizada; o teste exige que o segundo diálogo esteja fechado nesse caso rápido.

O grupo final de quatro testes passou duas vezes seguidas (11,4 / 16,8 segundos), em `release-0.8.69-loading-fix-2/`. O APK release final acima foi recompilado após a correção e teve versão, manifesto, assinatura e integridade conferidos novamente. Essas execuções focadas não substituem a regressão completa.

## Atualização e limites

Atualize por cima da release anterior para preservar dados e preferências. Não desinstale uma instalação existente por conflito de assinatura sem preservar os dados necessários. Não há publicação na Play Store nem inclusão de chaves/credenciais no Git. Releases e APKs históricos permanecem intactos.

Os testes automatizados de interface usam debug no emulador e não substituem confirmação complementar do APK release otimizado em aparelhos físicos. Codecs, dual áudio/legendas e variantes reais de Motion Photos continuam sujeitos à verificação no aparelho; precisão do OCR depende da qualidade da imagem e do texto.
