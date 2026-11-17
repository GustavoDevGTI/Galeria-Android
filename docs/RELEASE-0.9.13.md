# Galeria Android 0.9.13

Álbuns essenciais fixados com alfinete, opção de esconder nomes e data ao rolar.

- **Álbuns fixos desde o início:** Recentes, Favoritos, Câmera, Baixar, a pasta atual de Capturas de tela e a Lixeira já começam fixados no topo, também para quem atualiza. A Lixeira fica por último entre eles e só aparece quando tem conteúdo.
- **Alfinete discreto:** todo álbum fixado mostra um alfinete pequeno no canto da capa, branco com contorno escuro fino, visível em capas claras e escuras. Desafixar um álbum essencial (segurar → Fixar) tira o alfinete e o devolve à ordem comum; a escolha fica salva.
- **Esconder nomes:** em Configurações › Miniaturas, "Mostrar nome dos álbuns" controla o nome e a quantidade na tela principal, e "Mostrar nome dos arquivos" controla o nome sobre as miniaturas dentro dos álbuns. O modo lista mantém o nome.
- **Data ao rolar:** dentro de um álbum, o ano aparece no topo durante a rolagem. Ao segurar a barra de rolagem, um balão ao lado do dedo mostra mês e ano, e os anos ficam marcados ao longo da barra. Em álbuns ordenados por nome, tamanho, duração ou tipo, o balão mostra a letra inicial, o tamanho, a duração ou a extensão.

## APK

- Arquivo Galeria-Android-versao-0.9.13.apk
- Pacote com.galeria.android
- Versão 0.9.13, código 9013
- Tamanho 45388949 bytes
- SHA-256 do arquivo 6942f774e4d7d3f3f1fc1a8d2a5d1d6394ead035d794dd4796475c4fecbbf3b5
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261117-104840-830-63068936 (`-Delivery`): **154 testes de interface**, **304 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (142 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Os unitários novos cobrem a ordem dos álbuns fixos (Lixeira por último, álbum essencial desafixado na ordem comum), o fixar e desafixar, o texto do balão para cada ordenação e as marcas de ano.
- O teste de ordem dos álbuns confere quais álbuns mostram o alfinete; o teste da barra de rolagem confere o balão com mês e ano, as marcas de ano e o sumiço do balão ao soltar.
- O teste de álbuns ocultos automáticos passou a esperar a biblioteca de mídia do Android ficar quieta antes de cada varredura. Ele falhava às vezes porque a limpeza do teste anterior ainda alterava a biblioteca.

Pendente de conferência em aparelho real: o alfinete sobre capas claras e escuras, as duas chaves de nomes e o balão ao arrastar a barra de rolagem num álbum grande.
