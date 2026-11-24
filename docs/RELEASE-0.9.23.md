# Galeria Android 0.9.23

Juntar fotos e vídeos, relógio de 48 horas, Separar e agrupamento por tamanho.

- **Juntar:** na seleção de um álbum, o botão Menu da barra inferior traz Juntar. As mídias entram na ordem dos toques e viram um vídeo só, salvo na pasta da primeira mídia, com a data da junção. A junção continua em segundo plano com notificação, o que pode ser desligado em Configurações › Vídeos.
- **Editor único:** o mesmo editor serve para juntar e para cortar um vídeo sozinho. Quadrados arrastáveis definem a ordem, e a linha do tempo abaixo deles acompanha cada troca. Cortes no meio removem só um trecho da própria mídia. O "+" acrescenta mídias, e o tempo das fotos vai de 4 a 10 segundos. A qualidade é nativa por padrão, com 1080p e 720p como opção. Formatos misturados saem na vertical com bordas pretas. Fotos ficam mudas e vídeos mantêm o som. Um vídeo sozinho em MP4 é copiado sem recodificar e fica ao lado do original.
- **Transições:** o círculo entre dois quadrados escolhe Seco, Dissolver, Escurecer, Deslizar ou Zoom. A prévia mostra a transição, e o vídeo salvo sobrepõe as duas mídias por até 0,6 segundo.
- **Relógio de 48 horas:** os vídeos usados em uma junção ganham um relógio, visível na grade e no visualizador. As fotos ganham o relógio com a opção "Relógio também nas fotos". Tocar no relógio mantém a mídia. Ao fim do prazo, a mídia vai para a lixeira, mesmo com a lixeira desativada. Ela só é excluída definitivamente com "Relógio exclui definitivamente" ligado e a lixeira desativada. Quando o Android exige confirmação, uma notificação abre a pergunta do sistema.
- **Separar:** no menu de um vídeo juntado pelo app, Separar devolve as mídias sem duplicatas:
  - originais existentes são usados como estão, e os da lixeira são restaurados;
  - um vídeo cortado na montagem volta como vídeo novo;
  - o que foi apagado sai do próprio vídeo juntado;
  - com o relógio das fotos ligado, a foto original fica guardada dentro do vídeo e volta idêntica;
  - o vídeo juntado vai para a lixeira.
- **Agrupar por tamanho:** os álbuns agrupam também por tamanho do arquivo, em faixas de 1 MB a 3 GB. Cada grupo (tipo, extensão, dia, mês ou tamanho) tem um título acima das miniaturas, e a grade continua alinhada abaixo dos títulos.
- **Telas mais limpas:** textos explicativos saíram das telas fora das Configurações. Os diálogos de decisão e de permissão continuam.
- **Correção:** excluir ou separar a única mídia aberta no visualizador não fecha mais o app com erro.

## APK

- Arquivo Galeria-Android-versao-0.9.23.apk
- Pacote com.galeria.android
- Versão 0.9.23, código 9023
- Tamanho 45726477 bytes
- SHA-256 do arquivo 8113a5420289148912f21337863b8d77fe2f783991be678997cb179fbd319cbb
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261123-231623-390-00b13408 (`-Delivery`): **181 testes de interface**, **366 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (185 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Testes de interface novos:
  - **Junção:** a ordem, a pasta, a duração, a tela, o som e a montagem gravada no vídeo; cancelar não salva nada; as cores de Dissolver, Escurecer e Deslizar no meio da transição.
  - **Editor:** reordenar, mudar o tempo da foto, cortar no meio, escolher a transição e juntar pelo serviço.
  - **Relógio:** a lixeira com e sem lixeira ativa, a exclusão definitiva e o relógio que ainda não venceu; o toque no relógio da grade e do visualizador.
  - **Separar:** restaurar da lixeira, refazer a foto e o vídeo cortado, a foto guardada idêntica e o Separar pelo menu do visualizador.
- O smoke de OCR no APK otimizado (cópia assinada para testes) passou nos fundos claro e preto.
- Unitários novos cobrem a montagem, a linha do tempo do editor, as regras do relógio, o plano do Separar, as fotos guardadas no MP4 e as faixas de tamanho dos grupos.

Pendente de conferência em aparelho real:

- vídeos 4K, fotos HEIC e fotos giradas pela EXIF na junção;
- a confirmação do sistema no relógio e no Separar sem acesso total aos arquivos;
- no Android 10, o relógio não manda para a lixeira sozinho.
