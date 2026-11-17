# Galeria Android 0.9.16

Ajustes pedidos depois da 0.9.14: ordem personalizada de álbuns, recuperação de pastas protegidas e correções na data, na barra lateral e nas pastas novas.

- **Arrastar álbuns para reordenar:** na tela principal, segure um álbum e, sem soltar, arraste para outro lugar. Álbuns fixados só trocam de lugar entre si, e os não fixados entre si. A primeira vez que um álbum é arrastado, a ordenação muda para "Ordem personalizada", que agora aparece em Ordenar; escolher outra ordenação depois mantém a ordem salva para voltar a ela. O arrasto fica desligado enquanto uma pesquisa filtra os álbuns.
- **Pergunta secreta das pastas com senha:** ao proteger uma pasta, o app pede uma pergunta (de uma lista pronta ou escrita pela pessoa) e a resposta. No pedido de senha aparece "Esqueci a senha": a resposta certa abre a pasta e oferece criar uma senha nova, que pode ser cancelada para manter a antiga. A resposta não diferencia maiúsculas, acentos nem espaços a mais e fica guardada como código protegido, igual à senha. Pastas protegidas antes desta versão pedem a pergunta na próxima vez que forem abertas com a senha certa.
- **Data ao rolar só na ordem por data:** o balão com mês e ano ao lado do dedo, o ano no topo e as marcas de ano ao longo da barra aparecem quando o álbum está ordenado por data, ou na ordem personalizada enquanto nenhuma mídia foi reordenada à mão (ela segue a data). Em qualquer outra ordem, como por nome, nada disso aparece. Antes, um álbum ordenado por nome, como a Câmera, mostrava no balão a primeira letra do nome do arquivo.
- **Barra lateral como na Samsung:** arrastar para a direita em cima das fotos faz a barra de álbuns aparecer acompanhando o dedo; ao soltar, ela abre de vez se passou de um terço da largura ou se o arrasto foi rápido, e volta se não. Arrastar a partir da borda esquerda, na altura do meio da grade, também funciona: essa faixa fica reservada para o app em vez do gesto de voltar do Android, que continua normal no resto da borda. Corrigido também o puxar para atualizar, que no topo do álbum roubava o arrasto quando o dedo descia um pouco.
- **Criador de pastas:** a janela ocupa mais a altura da tela, com cabeçalho mais compacto, e a lista de pastas ganhou o espaço que sobra.
- **Nova pasta no Mover com cartão SD:** o criador aberto pelo Mover mostra também o cartão SD. Mover ou copiar para uma pasta do cartão grava o arquivo direto nele quando o app tem acesso total a arquivos.

## APK

- Arquivo Galeria-Android-versao-0.9.16.apk
- Pacote com.galeria.android
- Versão 0.9.16, código 9016
- Tamanho 45430224 bytes
- SHA-256 do arquivo b921df71386e5b1f2173609537124e33be58d3eee05452b2e277476c5d78b359
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261117-172623-836-1901ee0f (`-Delivery`): **159 testes de interface**, **317 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (154 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Testes de interface novos: a pergunta secreta abre a pasta com a resposta certa (ignorando maiúsculas e espaços), recusa a errada e troca a senha; uma pasta protegida antes pede a pergunta depois da senha certa; segurar e arrastar um álbum grava a ordem personalizada; a barra lateral abre arrastando por cima das fotos, inclusive com o dedo descendo um pouco no topo do álbum, e também a partir da borda esquerda.
- Unitários novos cobrem a ordem personalizada e os grupos de fixados, a comparação das respostas, as perguntas prontas, o caminho de pastas no cartão SD, o arrasto e a decisão de abrir a barra ao soltar, e quando a data aparece ao rolar.
- No emulador, conferido à mão: o criador de pastas com a lista maior, o arrasto de álbuns gravando a ordem e a barra lateral acompanhando o dedo e voltando quando solta antes de um terço.

Pendente de conferência em aparelho real: arrastar álbuns com o dedo, a barra lateral com a navegação por gestos do aparelho, a recuperação de uma pasta protegida e mover uma foto para uma pasta nova no cartão SD.
