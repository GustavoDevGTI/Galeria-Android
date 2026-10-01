# Roteiro manual — Galeria Android 0.8.60

Esta lista descreve resultados esperados, não resultados já aprovados no aparelho. A publicação foi solicitada pelo usuário para teste manual, com a validação final da interface pendente. Não considerar a versão integralmente homologada.

## Preparação

- [ ] Instalar o APK release 0.8.60 por cima da versão release anterior, sem desinstalar nem limpar dados. Confirmar que favoritos, álbuns fixados, tema e preferências continuam presentes. Se o Android recusar a atualização, registrar o erro antes de desinstalar.
- [ ] Usar o celular extra com mídias reais. Para excluir definitivamente, usar somente arquivos descartáveis; manter cópia externa de qualquer arquivo importante.
- [ ] Separar exemplos que já existam no aparelho: foto comum, documento legível, foto sem texto, imagem grande/vertical, vídeo curto, vídeo longo e, se disponíveis, vídeo dual áudio/legendado e Motion Photo. Marcar como **não testado** o formato que não estiver disponível.
- [ ] Anotar modelo, versão do Android e modo de acesso concedido à galeria.

## 1. Visualizador e navegação — prioridade alta

- [ ] Abrir um vídeo em uma pasta com várias mídias; deslizar para o próximo e o anterior. Repetir com o vídeo reproduzindo e pausado. A navegação não pode ficar presa no vídeo.
- [ ] Navegar foto → vídeo → foto e entre vários vídeos. Não deve continuar tocando áudio de uma mídia que já saiu da tela.
- [ ] Tocar uma vez para ocultar/exibir os controles; testar os gestos existentes de reprodução, zoom em fotos e retorno à grade. A nova linha do tempo não deve bloquear gestos fora dela.
- [ ] Abrir arquivo com nome longo: o nome deve ficar abreviado, com espaço antes do coração e do menu, sem sobreposição.
- [ ] Conferir coração no topo: favoritar/desfavoritar atualiza o estado e a coleção Favoritos.
- [ ] Compartilhar foto e vídeo; abrir as informações e conferir nome seguido de data, categorias legíveis e propriedades do arquivo correto.

## 2. Exclusão e recuperação — prioridade alta

- [ ] Excluir um vídeo aberto. Depois da confirmação, ele deve sair da pasta e o visualizador deve mostrar outro item ou voltar à grade, sem continuar exibindo o excluído.
- [ ] Reabrir a pasta, atualizar e reiniciar o app: o vídeo excluído não pode reaparecer fora da Lixeira.
- [ ] Localizar o vídeo na Lixeira e restaurar. Ele deve voltar à pasta original e reproduzir normalmente.
- [ ] Repetir com uma foto e com seleção de mais de uma mídia.
- [ ] Cancelar a confirmação de exclusão: o arquivo deve continuar intacto, visível e reproduzível.
- [ ] Repetir excluir/restaurar com uma mídia de pasta oculta ou não indexada, se disponível e com acesso completo. Na Lixeira, só deve aparecer ao habilitar a exibição de ocultos.
- [ ] Excluir definitivamente somente um item descartável da Lixeira. Verificar que ele deixa de aparecer e não pode ser restaurado pelo app.

## 3. Linha do tempo de vídeo — prioridade alta

- [ ] Abrir um vídeo: miniaturas devem aparecer progressivamente acima das ações inferiores, em ordem temporal, sem bloquear a reprodução.
- [ ] Tocar em diferentes pontos, inclusive perto do início e do fim. O quadro, o tempo atual e o marcador devem acompanhar a posição escolhida.
- [ ] Arrastar para frente e para trás, lentamente e rapidamente. O vídeo deve acompanhar o gesto, sem trocar de arquivo por engano.
- [ ] Fazer o arraste com o vídeo pausado: ao soltar, deve continuar pausado. Repetir durante reprodução: deve retomar a reprodução ao soltar.
- [ ] Em vídeo longo, segurar a linha do tempo para usar a janela de ajuste fino de 20 segundos. Conseguir escolher segundos próximos sem exigir uma miniatura por segundo.
- [ ] Deixar reproduzir normalmente: marcador e tempo avançam sincronizados. Testar reprodução/pausa, som e duração total.
- [ ] Alternar rapidamente entre vídeos de durações diferentes: não devem ficar miniaturas, marcador ou tempo do vídeo anterior.
- [ ] Reabrir o mesmo vídeo: miniaturas já armazenadas devem voltar sem nova demora excessiva. Testar também com vídeo longo e arquivo grande.
- [ ] Arrastar até o fim com o vídeo pausado: não deve saltar inesperadamente ao início. Deixar terminar normalmente e reabrir: não deve ficar preso no último quadro.
- [ ] Sair durante um vídeo e reabrir dentro do prazo: verificar retomada quando aplicável. Após mais de 12 horas, a posição antiga não deve ser restaurada.

## 4. Menu Editar e aparência — prioridade alta

- [ ] Abrir Editar numa foto: submenu centralizado, com margens e três ações reconhecíveis — cortar, girar e edição personalizada — representadas por ícones.
- [ ] Abrir e fechar cada uma das três ações, inclusive em sequência; nenhuma pode desaparecer ou parar de responder.
- [ ] Abrir o editor com tema claro e escuro da galeria: a área interna de edição deve continuar preta, com ícones legíveis.
- [ ] Conferir botões de voltar/salvar, barra de ferramentas, gestos de navegação do Android e teclado: nada deve ficar cortado ou sobreposto.
- [ ] Rolar a barra horizontal de ferramentas quando não couber inteira. Todas as ferramentas continuam acessíveis.

## 5. Corte, giro e tamanho da imagem — prioridade alta

- [ ] Entrar em Cortar: a imagem deve começar inteira, sem corte automático. Salvar sem ajustar não deve remover bordas.
- [ ] Arrastar cada canto e cada borda; arrastar o interior para deslocar a área. A região escolhida deve acompanhar o dedo e não sair da imagem.
- [ ] Testar Livre, Original, 1:1, 4:3, 16:9 e 9:16. As proporções fixas devem continuar fixas ao arrastar.
- [ ] Testar giro, espelhamento e redefinição do corte. Conferir fotos em pé e deitadas, inclusive fotos da câmera com orientação EXIF.
- [ ] Salvar o corte: comparar a cópia com a região selecionada; o original deve permanecer intacto.
- [ ] Testar Girar pelo acesso rápido do submenu e conferir o resultado na grade/visualizador. Essa ação rápida mantém o fluxo próprio de alteração/confirmação do arquivo, diferente de salvar uma cópia no editor.
- [ ] No editor personalizado, testar Redimensionar imagem: informar largura válida e verificar dimensões da cópia. Cancelar e informar valor inválido não podem causar travamento.

## 6. Texto, pincel e filtros — prioridade alta

- [ ] Tocar no ícone de texto: deve abrir uma caixa de digitação sobre a imagem, não apenas um diálogo separado.
- [ ] Digitar texto com acentos e mais de uma linha; concluir e posicionar o texto. O resultado salvo deve corresponder à prévia em posição, tamanho e cor.
- [ ] Abrir/fechar o teclado e alternar ferramentas: texto confirmado não deve sumir ou ser duplicado.
- [ ] Ativar o pincel e desenhar. Tocar repetidamente no ícone para alternar as seis combinações de espessura/tipo; a diferença deve ser visível.
- [ ] Escolher várias cores no espectro e desenhar novos traços. Traços anteriores devem manter cor e espessura originais; caneta e marcador devem ter aparência distinta.
- [ ] Desenhar perto das bordas: a pintura não deve escapar da imagem.
- [ ] Testar filtros e voltar ao estado sem filtro; testar limpar/redefinir e as ações de desfazer que estiverem disponíveis.
- [ ] Combinar corte, giro, texto, pincel e filtro; salvar e reabrir a cópia. Confirmar que o original não foi alterado pelo editor personalizado.
- [ ] Voltar sem salvar: não deve gerar uma cópia inesperada nem alterar o original. Tocar rapidamente em salvar não deve gerar duplicatas.

## 7. OCR / extração de texto — prioridade alta

- [ ] Abrir uma foto nítida de documento e esperar a análise: deve aparecer um ícone de texto quando houver conteúdo reconhecido.
- [ ] Tocar no ícone, selecionar um trecho e copiar; colar em outro aplicativo para conferir o conteúdo. Testar também Copiar tudo.
- [ ] Segurar uma área da foto sem iniciar arraste: deve acionar o reconhecimento de texto. Zoom e navegação normais não podem ser confundidos com esse gesto.
- [ ] Repetir com captura de tela, documento com letras pequenas e foto girada; anotar textos relevantes não reconhecidos. OCR não garante transcrição perfeita.
- [ ] Abrir imagem sem texto: não deve mostrar resultado antigo nem um ícone indevido; solicitação manual deve informar ausência de texto.
- [ ] Abrir OCR dentro da edição personalizada e copiar o resultado. Confirmar que a ferramenta permanece acessível no tema preto.
- [ ] Trocar rapidamente de foto durante a análise: resultado/ícone da imagem anterior não pode aparecer na atual.
- [ ] Repetir em modo avião: o modelo é local e o reconhecimento não deve depender de envio da imagem à internet.

## 8. Edição de vídeo e Motion Photos

- [ ] Abrir Editar num vídeo: tema preto, prévia e controles acessíveis, sem sobreposição com as barras do Android.
- [ ] Escolher início/fim do corte, reproduzir a prévia e usar a linha do tempo. A cópia salva deve conter o trecho escolhido, mantendo o original.
- [ ] Em vídeo dual áudio/legendado, conferir as trilhas da cópia. Se o formato não permitir preservá-las, o corte deve ser bloqueado com explicação, sem remover trilhas silenciosamente.
- [ ] Abrir uma Motion Photo compatível: a foto estática deve aparecer normalmente e oferecer a reprodução do movimento.
- [ ] Reproduzir, pausar e navegar pela linha do tempo do movimento; voltar à foto original sem corrupção do arquivo.
- [ ] Se disponíveis, repetir com Motion Photos JPEG e HEIC de fabricantes diferentes. Registrar fabricante/formato quando a detecção falhar.

## 9. Modo cinema e funções antigas

- [ ] Regressão da navegação (próxima atualização): no modo normal, passar fotos e vídeos para esquerda/direita e cima/baixo. Repetir depois de voltar do segundo plano e de fechar/reabrir o visualizador. Fazer um segundo toque durante a troca e interromper uma troca indo para outro app: ao retornar, a mídia exibida deve corresponder ao título/player e continuar permitindo navegação nos dois eixos.
- [ ] Entrar/sair do cinema pela claquete: orientação correta, transição suave e ícone aberto/fechado, sem brilho permanente de botão pressionado.
- [ ] Regressão das barras do sistema (próxima atualização): no cinema, relógio/notificações e navegação devem ficar ocultos mesmo com os controles de reprodução visíveis. Girar, abrir/fechar o menu de trilhas e retornar do segundo plano: devem continuar ocultos. Um gesto na borda deve revelá-los temporariamente; ao sair do cinema, as barras devem voltar normalmente.
- [ ] Alinhamento da reprodução (próxima atualização): play/pause deve ficar no centro, com tempos à esquerda e velocidade à direita, tanto no modo normal quanto no cinema. Abrir/fechar a linha do tempo e girar a tela não devem deslocá-lo; conferir também o alinhamento central ao reproduzir uma Motion Photo.
- [ ] Arrastar verticalmente em cada lateral no cinema: brilho de um lado e volume do outro; não devem interferir no scrubbing da linha do tempo.
- [ ] Escolher trilha de áudio e legenda num arquivo que realmente as possua. Confirmar a troca ouvindo/lendo o resultado.
- [ ] Ativar a preferência de cinema e de trilhas para o álbum; abrir outro vídeo compatível, sair e retornar. A preferência deve continuar aplicada somente no escopo esperado.
- [ ] Ativar/desativar reprodução aleatória e apresentação de fotos. Navegar, girar a tela e voltar: recursos e opções devem continuar disponíveis.
- [ ] Conferir repetição, silenciar, informações, abrir em outro app, renomear e compartilhar onde aplicáveis.

## 10. Seleção, pastas, ocultos e desempenho

- [ ] Selecionar por toque prolongado e, sem levantar o dedo, arrastar sobre vários itens. Deve selecionar continuamente, sem acionar atualizar álbum.
- [ ] Conferir o olho no canto inferior direito de cada mídia selecionada. Abrir por ele e voltar: a seleção anterior deve continuar intacta.
- [ ] Usar pinça sobre as miniaturas para mudar colunas. Não deve exigir várias tentativas nem perder a posição da grade.
- [ ] Ordenar por nome: primeiro toque crescente, segundo decrescente, seta acompanhando o estado. Testar demais opções e comparação com a lista de destinos de Mover.
- [ ] Mover arquivos entre pastas: a origem deve atualizar imediatamente. Ao mover todos, deve abrir o destino; nenhuma pasta vazia deve ficar exibida.
- [ ] Conferir Favoritos apenas com itens, Recentes sem duplicação física e álbuns fixados antes dos essenciais.
- [ ] Mídias de pastas ocultas não devem aparecer em Recentes/Favoritos ou outras coleções; conferir também pastas com .nomedia e caches de miniaturas/documentos.
- [ ] Regressão de caches de documentos (próxima atualização): um cache já reconhecido com pastas como `1d`, `e7` e `9f` deve continuar oculto depois de ganhar mais de três imagens por pasta, atualizar a galeria ou trocar filtros de mídia. Conferir também Recentes, Favoritos e Lixeira com exibição de ocultos desativada; uma pasta comum com nome curto não deve sumir por engano.
- [ ] Usar o olho para revelar temporariamente um álbum oculto: deve aparecer na página inicial, mantendo o isolamento do conteúdo. Após 30 minutos, incluindo tempo em segundo plano, deve ocultar novamente. Fechar o app pelos recentes e reabrir deve encerrar a revelação antes do prazo.
- [ ] Conferir animações de olho/pin/checkbox e OK à direita no submenu de ocultos. Escolhas simples em outros submenus devem aplicar sem OK/Cancelar redundantes.
- [ ] Conferir capas automáticas pela ordenação do álbum e capas manuais; reabrir pastas várias vezes e observar se thumbs permanecem estáveis, nítidas e rápidas.
- [ ] Conferir contraste do relógio/notificações, cores do tema e configurações essenciais/avançadas.
- [ ] Testar acesso limitado a fotos, quando disponível, sem limpar dados: mostrar somente mídias autorizadas e pedir permissões adicionais apenas nas ações pertinentes.
- [ ] Usar o app por alguns minutos alternando edição, OCR, vídeos longos e galeria. Registrar travamentos, aquecimento incomum, demora crescente ou áudio continuando em segundo plano.

## Como informar um problema

Copiar este modelo para cada falha:

```text
Seção/item:
Modelo e Android:
Versão do app: 0.8.60
Tipo de mídia (formato, resolução/duração; dual áudio/Motion Photo se aplicável):
Passos exatos:
Esperado:
O que aconteceu:
Acontece sempre ou às vezes?
Print ou gravação (se possível):
```

Priorizar qualquer perda de arquivo, arquivo original alterado inesperadamente, exposição de ocultos, travamento ou recurso que tenha desaparecido. Se isso ocorrer, interromper a operação afetada até investigar.
