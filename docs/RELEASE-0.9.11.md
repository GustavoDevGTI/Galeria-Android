# Galeria Android 0.9.11

Correções da revisão do código, sem mudar a aparência do aplicativo.

- **Compartilhar álbuns grandes:** o Android aceita no máximo cerca de 1 MB por envio entre apps, e milhares de mídias fechavam a Galeria. O compartilhamento agora envia até 500 mídias e avisa quando a seleção era maior.
- **Abrir com outro app:** quando outro aplicativo abre uma foto ou vídeo na Galeria, só o arquivo enviado é usado. Opções internas como incluir arquivos ocultos, álbum de origem ou modo aleatório são ignoradas. As aberturas feitas pela própria Galeria levam uma chave aleatória guardada nos dados privados do app.
- **Barras do sistema:** a tela principal, a de álbum e o visualizador usam as medidas de barra de status e de navegação informadas pelo Android, em vez de um recurso interno. Esconder as barras no modo cinema não desloca a tela. No visualizador deitado, o espaço lateral segue a barra de navegação real.
- **Miniatura flutuante:** o Android passa a saber onde o vídeo está na tela, e a animação de entrada parte do vídeo em vez de mostrar o ícone do app.
- **Organizador:** quando algumas mídias não são salvas, a mensagem diz quantas foram. Textos de uma versão antiga do organizador, que nunca apareciam, foram removidos.
- **Editor de imagem:** o espectro de cores e o recorte não criam objetos a cada quadro desenhado, o que deixa o arraste mais leve.

## APK

- Arquivo Galeria-Android-versao-0.9.11.apk
- Pacote com.galeria.android
- Versão 0.9.11, código 9011
- Tamanho 45358382 bytes
- SHA-256 do arquivo b9721ef3be3c67e0f8cef6ecc43f4943985827f3306bc7cd8bd2fa258eb9406d
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

**Nenhum teste em emulador foi executado nesta versão**, por decisão do responsável pelo projeto; a conferência é feita em aparelho real. Não há recibo de entrega para a 0.9.11 e a regressão completa (`-Delivery`) não rodou.

O que rodou:

- Execução 20261116-104444-754-56ab85b9, sem emulador: 54 contratos das ferramentas e **296 testes unitários**, sem falhas ou ignorados.
- Compilação dos testes instrumentados, lint sem erros e montagem release otimizada.

Os testes novos cobrem o limite de compartilhamento, a verificação da chave das aberturas internas e a lista de opções descartadas quando outro app abre o visualizador.

Pendente de conferência em aparelho real: compartilhar um álbum grande, abrir uma foto por outro app, conferir o topo e a base das três telas em pé e deitado, e entrar na miniatura flutuante com um vídeo tocando.
