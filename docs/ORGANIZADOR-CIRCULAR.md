# Organizador circular

Na versão **0.9.2** (`versionCode` 9002), Organizar abre a escolha do álbum de origem e entra diretamente na roda. Filtrar continua no menu de três pontos.

## Roda e gaveta

A roda tem oito posições: a **Lixeira** é fixa embaixo e até sete álbuns físicos ocupam as demais. Posições vazias mostram **+**. A configuração anterior reaproveita até sete destinos, preservando o espaço da lixeira.

A gaveta fica abaixo da roda, com espaço reservado no layout e rolagem própria. A seta ou um gesto vertical sobre o puxador abre e fecha a gaveta. A roda se ajusta sem ser coberta. **Nova pasta**, com miniatura **+**, é o primeiro item e abre o fluxo de criação de pasta do app. Os demais itens usam as capas e nomes do menu Mover.

Segure e arraste um álbum da gaveta para uma posição da roda. Sobre uma posição ocupada, o álbum anterior volta à gaveta. Segure um álbum da roda para trocá-lo de posição ou devolvê-lo à gaveta. A lixeira é fixa e a origem não aparece como destino. Menus de toque também oferecem essas ações.

## Marcar e salvar

Empurrar ou arrastar uma mídia registra o destino e mostra imediatamente a próxima, sem alterar o arquivo. Cada marcação captura o álbum escolhido: retirar ou substituir um álbum da roda não muda as marcações anteriores. Os contadores incluem as mídias direcionadas na sessão.

**Desfazer**, acima da roda, cancela a última marcação não salva. **Salvar**, na barra superior, aplica movimentos e exclusões. Ao sair com alterações pendentes, aparecem três opções separadas: **Salvar e sair**, **Sair sem salvar** e **Continuar**. Descartar mantém os arquivos na origem.

No salvamento, a Lixeira consulta a configuração atual: habilitada significa exclusão recuperável; desabilitada significa exclusão permanente. Nenhuma exclusão ocorre durante o arrasto. Falhas mantêm a marcação para tentar novamente ou desfazer. Arquivos já salvos não são repetidos nem pertencem mais ao desfazer da sessão.

A reserva mostra a última mídia guardada e se transforma no próprio carrossel. Enviar uma mídia reservada não consome a fila central. A reserva persiste; marcações de arquivo ainda não salvas ficam na sessão. Após morte do processo, os arquivos não salvos continuam na origem.

## Prévia

Tocar na mídia central amplia a foto **reta**, respeitando a proporção, com o entorno desfocado. Outro toque ou Voltar fecha a foto. Vídeos usam o mesmo fundo e iniciam a reprodução com apenas play/pausa central e barra de progresso. O player é liberado ao fechar ou sair da tela.

## Estrutura e validação

- `OrganizerController` mantém posições protegidas, marcações, contadores, reserva e salvamento serial fora da thread da interface. A rotação preserva a sessão.
- `OrganizerMediaMover` valida caminhos, resolve colisões e aplica movimentos, lixeira e exclusão, usando a lixeira recuperável existente quando necessário.
- `OrganizerBoard` desenha a roda, gestos, doze imagens antecipadas e transições.
- `OrganizerAlbumDrawer` mantém os álbuns e sua rolagem independente.
- `OrganizerVideoPreview` limita os controles e usa textura transparente sobre o fundo da prévia.
- `OrganizerActivity` cuida da origem, permissões, criação de pasta e confirmação de saída.

Os testes usam arquivos reais para verificar marcações sem escrita, salvamento, falha parcial, tentativa seletiva, descarte, lixeira, migração, arrasto, rolagem, rotação, foto e vídeo. A entrega completa usa `scripts/run-validation.ps1 -Delivery`; o resultado e o APK estão nas [notas da 0.9.2](RELEASE-0.9.2.md).
