# Galeria Android 0.8.67

## Mudanças

- Corrigido o carregamento dos destinos de Mover quando uma pasta oculta está visível temporariamente. O seletor deixa de reconciliar todas as pastas conhecidas pelo catálogo completo de ocultos antes de filtrar a lista.
- A consulta ao filesystem fica limitada aos destinos físicos expostos que precisam dela; não relê a origem nem outros ocultos sem relação com o pedido. Álbuns indexados continuam vindo do MediaStore.
- Correção compartilhada entre a seleção de arquivos no álbum e Mover/Copiar no visualizador.
- Preservados a ordem exibida na página inicial, contagens atuais dos destinos, regras de ocultação, duração da revelação temporária, indicador circular e bloqueio de pedidos duplicados.
- Teste de regressão exige que os dois seletores entreguem os destinos corretos, sem abrir vídeos da origem/ocultos não expostos e sem usar a contagem antiga do destino oculto.

Nenhuma funcionalidade foi intencionalmente removida. Nenhuma dependência nova foi adicionada. O problema e o limite do diagnóstico estão documentados em [MOVER-OCULTOS-2026-10-13.md](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.67/docs/MOVER-OCULTOS-2026-10-13.md).

## Validação

Com versão `0.8.67` e código `8067` definidos, a regressão completa passou em **229/229 testes unitários e 115/115 testes instrumentados**, sem falhas/ignorados, no emulador dedicado Android 16/API 36 com SwiftShader. Lint: zero erros e 129 avisos; 45 contratos das ferramentas de validação aprovados.

Execução final `20261013-100723-332-a870e465`: 472,6 segundos de validação, sendo 444,1 segundos de instrumentação. Evidências locais: `app/build/reports/validation/20261013-100723-332-a870e465/result.json` e `app/build/reports/validation/delivery.json`. O portão de entrega verifica os fontes e hashes dos APKs/relatórios; os recibos parciais não foram usados para aprovar a publicação.

A build release otimizada e assinada também passou. Pacote `com.galeria.android`, versão `0.8.67`, código `8067`, sem Activity/runner exclusivos dos testes e sem modo depurável no manifesto; assinatura válida, com o mesmo certificado da 0.8.66.

- Arquivo: `Galeria-Android-versao-0.8.67.apk`
- Tamanho: 45.120.301 bytes
- SHA-256: `59a815b493796e92ffe2526a85b437599580a501ec7a039889dee348dc08569b`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Evidências da build/assinatura: `app/build/reports/release-0.8.67-{build,signature,package,manifest}.txt`. A suíte de interface usa debug e não substitui a verificação complementar de codecs, dual áudio/legendas e variantes de Motion Photos no celular físico. Não foi feita medição de latência na biblioteca do telefone do usuário.

A tentativa `20261013-093734-770-6529f928` foi rejeitada após o emulador parar de responder inclusive a comandos básicos; o AVD dedicado foi reiniciado sem apagar dados. A tentativa seguinte (`20261013-094609-283-0ba7a945`) terminou em 114/115: o teste de arraste esperava três selecionados, mas obteve dois. Não foi promovida a entrega.

Esse teste presumia que os nomes da primeira/terceira inserção ocupavam os extremos da grade, usando datas sintéticas no catálogo, embora o álbum físico consulte o MediaStore. Agora verifica os três itens disponíveis e injeta o mesmo gesto real entre a primeira e a última posição da ordem efetivamente exibida. Mantém a exigência de três selecionados, ausência de pull-to-refresh e preservação da seleção após visualizar. O caso ajustado passou três vezes seguidas; é necessária a nova aprovação completa, não só essa repetição focada.

A tentativa `20261013-095631-759-c852ae12` registrou falha no teste do indicador (diálogo já ausente) e foi encerrada antecipadamente para diagnóstico, sem aprovação. Sua operação simulada liberava automaticamente o bloqueio após 10 segundos. A fixture agora permanece bloqueada até a liberação explícita pelo teste, garantida pelo `finally`, em vez de depender da velocidade do emulador. As esperas de conclusão continuam limitadas e as verificações de indicador/duplicação permanecem; timeouts do app não foram alterados.

A tentativa `20261013-100204-752-ec8f685d` também foi rejeitada após falha ao destruir a Activity de teste e encerrada para recuperação do emulador. O DropBox do Android registrou `system_server_watchdog` às 13:05:50 UTC de 13/10: `WindowManagerService`, display e animação bloqueados por 70 segundos, com captura síncrona de snapshot de janela na pilha. Evidência local: `app/build/reports/emulator-0.8.67-watchdog.txt`. O AVD foi reiniciado sem apagar dados, usando `-gpu swiftshader -no-snapshot-load -no-snapshot-save -no-audio -no-boot-anim`; nenhuma asserção foi removida para contornar esse travamento do sistema. Isso não comprova uma causa única para todas as tentativas anteriores.

## Atualização

Atualize por cima da release anterior para preservar seus dados e preferências. Não desinstale uma instalação existente por conflito de assinatura sem preservar seus dados. Não há publicação na Play Store nem inclusão de chaves/credenciais no Git. Releases e APKs históricos permanecem intactos.
