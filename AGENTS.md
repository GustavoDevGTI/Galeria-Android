# Validação durante o desenvolvimento

- Em mudanças do aplicativo, execute a base diária (`scripts/run-validation.ps1`): todos os unitários, lint/build e quatro integrações essenciais. Acrescente testes focados no comportamento alterado com `-Area` e/ou `-Test Classe#metodo`. A base já é incluída e os casos duplicados são removidos.
- Escolha os casos específicos relevantes; o pacote curto da área é um ponto de partida, não substitui testar o comportamento novo. Use `-List` para conferir a seleção sem executar testes.
- A bateria extensa de uma feature só deve rodar quando houver suspeita concreta de bug nela: `-Extended -Area <area>`. Use `-Extended -Area All` apenas se a suspeita atravessar o aplicativo inteiro.
- Execute a regressão completa com `-Delivery` e confira `assert-delivery-validation.ps1` antes de uma release no GitHub. Mudanças locais, APKs de prévia e pushes comuns não exigem essa bateria completa. Publicar continua dependendo da autorização do usuário.
- Alterações somente em documentação dispensam testes Android. Para mudanças nas ferramentas de validação, execute seus contratos e confira os planos afetados; não abra um emulador nem rode a suíte completa sem um risco que justifique isso.
- `-UnitOnly` serve para iteração rápida sem emulador; não representa a base diária completa. Se faltarem aparelho ou ferramentas, informe quais verificações não rodaram, sem promover uma seleção parcial a aprovação de release.
- Uma falha deve ser investigada. Repita apenas os casos relevantes até resolver; não dispare toda a bateria a cada correção. A nova aprovação completa fica para a publicação.
- Preserve os testes extensos e suas asserções. Não remova cobertura, aceite ignorados, faça retries automáticos ou reduza o portão de release para economizar tempo.

Detalhes e comandos: [docs/TESTES-VALIDACAO.md](docs/TESTES-VALIDACAO.md).

## Emuladores de teste — manutenção de 06/11/2026

- Reutilizar somente o AVD padrão `Galeria_Codex_Test_36` pela rotina `scripts/start-test-emulator.ps1`; não criar variantes por versão/rodada nem recriar `Galeria_API_36`. O runner prepara a instância da porta 5554 sem snapshots; preservar dados do AVD e isolamento do Downloader. Ver `docs/EMULADORES-TESTE.md`.
