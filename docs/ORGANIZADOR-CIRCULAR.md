# Organizador circular

O organizador faz parte do aplicativo na versão de código **0.9** (`versionCode` 9000). A [release v0.9](RELEASE-0.9.md) registra o APK já publicado. Os refinamentos desta tela posteriores à release entram no próximo pacote publicado.

O atalho **Organizar** fica na barra da galeria e dos álbuns. **Filtrar** permanece no menu de três pontos.

## Uso

1. Escolha um álbum físico de origem. A lista usa as mesmas capas, nomes e cores do menu **Mover para álbum**.
2. Escolha de **1 a 8 destinos** e toque em **Começar a organizar**. A ordem escolhida define os setores a partir do topo, no sentido horário. A última configuração da origem é lembrada. Também é possível criar uma pasta nessa etapa.
3. Empurre ou arraste a mídia central na direção de um setor. Segure um setor e arraste sobre outro para trocar as posições. O setor recebe um pequeno movimento radial; o destino do último envio usa o contraste do tema.

A reserva fica acima do círculo. Arrastar a mídia até ela a guarda na sessão sem mover o arquivo. O botão mostra a última mídia reservada em tamanho destacado e se transforma no próprio carrossel de miniaturas; nele é possível rolar lateralmente, selecionar e arrastar uma miniatura para um destino.

Toque na mídia central para ampliá-la em perspectiva, com o entorno desfocado. Toque novamente ou use **Voltar** para fechá-la. A prévia não altera a fila. A próxima mídia aparece no centro assim que a anterior é enviada, sem aguardar a animação ou a gravação. **Desfazer** fica na parte inferior da tela.

Cada setor mostra quantas mídias recebeu **nesta sessão**. O número aumenta após um movimento concluído com sucesso, acompanha o álbum quando setores são trocados e diminui ao desfazer. Falhas não alteram o contador.

## Estrutura e integridade

- `OrganizerRules` define seleção, setores, gestos, trocas e fila recuperável.
- `OrganizerController` mantém fila, reserva, contadores e histórico; preserva a sessão durante rotação e serializa os movimentos fora da thread da interface.
- `OrganizerMediaMover` retorna a identidade real do arquivo movido, inclusive quando resolve uma colisão de nomes. O desfazer usa essa identidade. O destino é validado dentro do armazenamento e o caminho direto usa renomeação atômica sem sobrescrever arquivos.
- `OrganizerBoard` desenha o círculo, gestos e animações. Antecipam-se doze imagens, com três posições visíveis na pilha. O carrossel carrega apenas as miniaturas necessárias.
- `OrganizerActivity` cuida da seleção e das permissões. No Android 11 ou superior, solicita acesso de gerenciamento de arquivos antes dos movimentos contínuos.

O arquivo só sai fisicamente da origem quando a operação termina com sucesso. Uma falha devolve a mídia à fila ou à reserva. A reserva e a ordem dos destinos são persistidas; contadores e histórico de desfazer pertencem à sessão. Após morte do processo, a origem é consultada novamente, sem repetir movimentos antigos.

## Validação

`OrganizerRulesTest` cobre seleção e gestos para 1 a 8 destinos. `OrganizerInstrumentedTest` usa arquivos reais para verificar envio, reserva, colisões, falhas, contadores, troca de setores, rotação, prévia e desfazer. A entrega completa usa `scripts/run-validation.ps1 -Delivery` e confere os testes das demais áreas do aplicativo, além de lint e build.

Em 23/10/2026, a execução `20261023-112348-672-da1044d0` aprovou **250 testes unitários, 142 instrumentados e 45 contratos das ferramentas**, sem falhas ou ignorados. Lint: zero erros e 133 avisos. A checagem independente `scripts/assert-delivery-validation.ps1` aceitou o recibo. Isso valida o APK debug; o APK da release publicada continua descrito nas [notas da v0.9](RELEASE-0.9.md).
