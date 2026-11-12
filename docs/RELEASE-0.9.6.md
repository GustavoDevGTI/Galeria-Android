# Galeria Android 0.9.6

- **Ícone novo:** a lente no centro da roda mostra montanhas em camadas sob um sol, no lugar da flor. O ícone continua acompanhando a família de cor do tema (padrão, rosa, âmbar, verde, azul ou violeta), e o ícone monocromático do Android 13+ mostra a roda, o sol e as montanhas.
- **Vibrações por tipo de efeito:** cada interação responde de um jeito, sempre respeitando o retorno por toque do celular.
  - Toque leve nos botões das barras inferiores e nas opções de menus e diálogos.
  - Passo ao marcar ou desmarcar itens, ao arrastar a seleção e ao trocar a velocidade do vídeo.
  - Ligar e desligar em favoritos, som do vídeo, modo cinema e chaves das Configurações.
  - Confirmação ao aplicar ou restaurar o tema, definir a capa do álbum e no botão principal dos diálogos.
  - Recusa quando falha criar pasta, girar imagem, carregar a linha do tempo do vídeo ou reconhecer texto.
  - Marcação quando o vídeo vira miniatura flutuante.
- **Miniatura flutuante:** abre somente pelo **Voltar** com o vídeo tocando. O botão de início volta a pausar o vídeo, como antes da 0.9.5.

## APK

- Arquivo Galeria-Android-versao-0.9.6.apk
- Pacote com.galeria.android
- Versão 0.9.6, código 9006
- Tamanho 45204681 bytes
- SHA-256 do arquivo 2974eac40443e346ab3b24c9ff1b65d5b38177b2c8f7ecfa5c061e183b2d8c2d
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

**A regressão completa no emulador (`-Delivery`) não foi executada nesta versão**, por decisão do responsável pelo projeto; a conferência final é feita em aparelho real. Não há recibo de entrega para a 0.9.6.

O que rodou:

- Execução 20261112-153016-514-f3e9c5dc, sem emulador: 54 contratos das ferramentas e **262 testes unitários**, sem falhas ou ignorados.
- Lint sem erros, com 150 avisos, e montagem release otimizada. A montagem preservou os três registradores do OCR e seus construtores.
- Antes da troca de versão, em 12/11/2026, a base diária mais as áreas de Aparência, Reprodução, Cinema e Álbuns rodaram 18 testes instrumentados no Android 16 (API 36) com estas mudanças. A verificação do ícone monocromático foi atualizada da flor para céu transparente, sol e montanhas, e a classe do tema passou.
- As 6 variantes do ícone foram conferidas no emulador pela roda de temas.

Pendente de conferência em aparelho real: a sensação das vibrações (o emulador não tem motor de vibração), os testes de OCR no código otimizado e os codecs de vídeo.
