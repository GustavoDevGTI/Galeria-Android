# Galeria Android 0.9.15

Painel de ícones no lugar do menu de três pontos e barra inferior editável.

- **Painel de ícones:** o botão de três pontos da tela principal e da tela de álbum abre um painel por cima da tela, acima da barra inferior, com as opções em grade de quatro por linha. Cada opção tem um ícone próprio. Tocar numa opção fecha o painel e a executa; tocar fora ou voltar fecha o painel.
- **Opções da tela principal:** Filtrar mídia, Organização de pastas, Espaçamento entre álbuns, Criar nova pasta, Exibir itens ocultos da Lixeira, Atualizar, Lixeira e Tema, além de Ordenar, Organizar, Ocultos e Configurações, que começam na barra.
- **Opções da tela de álbum:** Filtrar mídia, Agrupar por, Modo de visualização, Criar nova pasta, Escolher capa, Usar capa automática, Exibir itens ocultos da Lixeira, Espaçamento da grade, Modo cinema e Tema, além de Ordenar, Organizar, Aleatório e Configurações, que começam na barra. As opções seguem as mesmas regras de antes: a Lixeira não oferece capa nem reprodução aleatória, e o Modo cinema só aparece onde é suportado.
- **Barra inferior editável:** com o painel aberto, segurar um ícone e arrastar para a barra cria um atalho na posição em que ele for solto, e ele sai do painel. Segurar um atalho da barra e arrastar para o painel tira ele da barra e o devolve ao painel. Dentro do painel e dentro da barra, arrastar só muda a ordem; nenhuma opção some. A barra aceita de 1 a 5 atalhos. A tela principal e a de álbum guardam cada uma a sua barra e a sua ordem.
- **Tema:** a opção Tema abre direto a escolha de cor das Configurações.

## APK

- Arquivo Galeria-Android-versao-0.9.15.apk
- Pacote com.galeria.android
- Versão 0.9.15, código 9015
- Tamanho 45403094 bytes
- SHA-256 do arquivo aadedd3e63e0f6f35f8036d2604b212bb8559fd2ee7c9226f40522073c83a771
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261117-153419-509-08d5cef3 (`-Delivery`): **156 testes de interface**, **311 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (151 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Teste de interface novo: abre o painel, confere as opções com ícone, arrasta Tema do painel para a barra e de volta, e confere que a escolha continua salva ao reabrir a tela.
- Os testes da barra, do menu, das capas, do modo cinema por álbum e do criador de pastas passaram pelo painel novo.
- Unitários novos cobrem a divisão entre barra e painel, o limite de 1 a 5 atalhos, a troca de ordem e a leitura de escolhas salvas quando opções mudam entre versões.

Pendente de conferência em aparelho real: arrastar ícones entre o painel e a barra com o dedo, nas duas telas, e o painel em telas pequenas ou com fonte grande.
