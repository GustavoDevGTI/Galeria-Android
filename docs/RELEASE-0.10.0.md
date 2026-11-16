# Galeria Android 0.10.0

Todas as opções das Configurações passam a funcionar. As que antes só guardavam a escolha agora mudam o aplicativo.

- **Senha do aplicativo:** crie, altere ou remova em Configurações > Segurança. Ela libera três proteções: abrir a galeria, ver os itens ocultos e excluir, mover, renomear ou ocultar arquivos. Remover a senha desliga as três.
- **Pastas seguras:** selecione um ou mais álbuns e toque no cadeado para definir uma senha só deles. Sem essa senha a pasta não abre. A capa mostra um cadeado e as mídias da pasta não aparecem em Recentes, Favoritos nem na pesquisa. A senha é guardada apenas como hash com sal e **não há como recuperar uma senha esquecida**. Depois de 30 segundos com o app em segundo plano, tudo pede a senha de novo.
- **Operações de arquivo:** apagar pastas vazias (pastas do sistema e pastas com itens na Lixeira são mantidas), manter a data de modificação ao mover ou copiar e pular a confirmação ao enviar para a Lixeira. Exclusões definitivas continuam pedindo confirmação.
- **Visualizador:** fundo preto ou do tema, esconder as barras do sistema, exibir o notch, trocar de mídia tocando nas laterais, brilho deslizando no lado esquerdo, fechar arrastando a foto para baixo, girar a foto com dois dedos, zoom aprofundado, zoom 1:1 com dois toques duplos, qualidade máxima, detalhes do arquivo abaixo do nome, mostrar ou esconder a barra de ações e abrir vídeos sozinhos. Fechar arrastando para baixo e girar com dois dedos começam desligados, para o deslizar entre mídias continuar igual.
- **Miniaturas:** cortar em quadrado ou mostrar a mídia inteira, GIFs animados na grade (Android 9 ou mais recente), estilo dos cantos para arquivos e pastas, rolagem horizontal em linhas, puxar para atualizar e prioridade de carregamento.
- **Idioma:** português, inglês e espanhol, escolhidos dentro do app. Trocar o idioma reabre a galeria. O padrão continua português.
- **Formato de data e hora:** vale para as datas da ficha de informações e dos detalhes em tela cheia.
- As escolhas de lista passam a ser salvas como códigos fixos. As escolhas feitas em versões anteriores continuam valendo.

Ainda sem função: gerenciar pastas ignoradas, filtro de fotos, botões visíveis e exportar favoritos.

## APK

- Arquivo Galeria-Android-versao-0.10.0.apk
- Pacote com.galeria.android
- Versão 0.10.0, código 10000
- Tamanho 45359514 bytes
- SHA-256 do arquivo 26fd35320d80ab8f7ae4cb1c3057d1132db61daa91f331953a3de3b6abe7a1ba
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

**Nenhum teste em emulador foi executado nesta versão**, por decisão do responsável pelo projeto; a conferência é feita em aparelho real. Não há recibo de entrega para a 0.10.0 e a regressão completa (`-Delivery`) não rodou.

O que rodou:

- Execução 20261113-192030-355-d614f92e, sem emulador: 54 contratos das ferramentas e **293 testes unitários**, sem falhas ou ignorados.
- Compilação dos testes instrumentados, lint sem erros e montagem release otimizada.

Os testes novos cobrem o hash das senhas, as proteções que dependem da senha do app, o bloqueio depois do tempo em segundo plano, o alcance das pastas seguras, os gestos novos do visualizador, as regras de pastas vazias e data de modificação, a leitura das escolhas salvas por versões antigas, a prioridade de carregamento, o estilo das miniaturas e o idioma.

Pendente de conferência em aparelho real: criar a senha do app e cada proteção, criar e abrir uma pasta segura, trocar o idioma e conferir as opções do visualizador e das miniaturas.
