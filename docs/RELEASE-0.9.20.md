# Galeria Android 0.9.20

Revisão visual, botão Menu na barra inferior e pesquisa pelo texto escrito dentro das fotos. Nada mudou de lugar: a revisão muda só a aparência e o movimento.

## Revisão visual

- **Pastas ocultas:** o painel "Exibir/ocultar pastas" segue o padrão dos outros menus. As linhas deixaram de ser blocos coloridos: só as pastas fixadas ou exibidas pelo olho ganham um fundo suave e arredondado, e as demais mostram apenas o toque. A quantidade de cada pasta aparece em tom mais discreto que o nome. "Carregar ocultos" virou um botão de contorno, e o OK é uma faixa limpa separada por uma linha fina. A lista ocupa toda a altura do painel, sem o espaço vazio que sobrava acima do OK.
- **Cores de seleção:** as caixas de marcar, o alfinete fixado, o olho ativo e o OK usam a cor de destaque do tema quando ela tem bom contraste com o fundo; caso contrário, usam a cor do texto, como os demais menus.
- **Animações:** o app passou a usar uma única curva de movimento, com durações curtas. Os painéis centrais crescem do centro e os painéis laterais saem do próprio canto. O painel do Menu sobe da barra com o fundo escurecendo junto e desce do mesmo jeito ao fechar. O toque nos botões ficou mais rápido e firme. Os controles do painel de ocultos se acomodam sem o efeito de quique. Quando as animações estão desligadas no Android, o movimento é removido e fica só o esmaecimento.

## Botão Menu

- O fim da barra inferior tem um botão **Menu** fixo, que abre e fecha o painel de opções. Os três pontinhos do topo aparecem só no modo de seleção (e ao escolher a capa do álbum).
- A barra tem até quatro atalhos mais o Menu, que não pode ser arrastado nem removido.

## Pesquisa pelo texto das fotos

- **Leitura em segundo plano:** desde a primeira abertura, o app lê o texto das fotos no próprio celular, sem internet, das mais novas para as mais antigas, e guarda num índice local. A leitura roda a qualquer momento, em rodadas de alguns minutos, e continua de onde parou. Depois de pronto, o índice só acompanha as mudanças: fotos novas ou editadas são lidas, e fotos apagadas saem dele. Para não pesar, o índice lê cada foto reduzida e numa orientação só, já endireitada pela orientação gravada no arquivo.
- **Pesquisa na tela principal:** ao digitar na busca, aparece logo abaixo "Procurar “palavra” no texto das fotos". Tocar nela, ou na tecla de pesquisar do teclado, abre "Resultados da pesquisa" com as fotos que têm todas as palavras digitadas e a quantidade encontrada. Maiúsculas, acentos e espaços não importam. Pastas protegidas por senha nunca aparecem nos resultados. Enquanto o índice ainda está sendo montado, uma pesquisa sem resultado mostra quantas fotos já foram lidas.
- **Pesquisa dentro do álbum:** a busca de um álbum passa a encontrar também as fotos daquele álbum pelo texto, além do nome do arquivo.
- **Configurações:** em Geral, "Pesquisar no texto das fotos" liga e desliga a leitura, e "Texto lido" mostra quantas fotos já foram lidas.

## APK

- Arquivo Galeria-Android-versao-0.9.20.apk
- Pacote com.galeria.android
- Versão 0.9.20, código 9020
- Tamanho 45453003 bytes
- SHA-256 do arquivo b00ec1e10f3022d31bf17605ce6f7a0841f5ef5b3173f49caa3055f1f6320495
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261119-192524-624-f5a997c6 (`-Delivery`): **161 testes de interface**, **321 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (156 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.

- Teste de interface do índice: uma foto com "NOTA FISCAL GIRASSOL" é lida e achada por "Girassol" e por "nota girassol" (e não por "girassol orquídea"); a linha da tela principal abre os resultados com "1 foto"; a foto apagada sai do índice.
- Teste de migração: o banco do app sobe da versão 3 para a 4 mantendo o catálogo e criando o índice vazio.
- Testes da barra e do painel: o Menu é o último item da barra, abre e fecha o painel e não entra no arrasto; os atalhos continuam indo e voltando entre a barra e o painel.
- O painel de ocultos mantém as posições conferidas pelos testes: centralizado com margens iguais, "Exibir ocultos" e "Carregar ocultos" na mesma linha e o OK ocupando toda a largura na base.
- Durante os testes de interface a leitura em segundo plano fica desligada, para não disputar o processador; só o teste do índice a liga.

Pendente de conferência em aparelho real: o tempo de leitura das fotos do celular, a pesquisa por textos reais (documentos, prints, etiquetas), o consumo de bateria durante a leitura e a sensação das novas animações na tela do aparelho.
