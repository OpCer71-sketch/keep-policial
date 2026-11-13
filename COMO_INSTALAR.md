# Keep Policial — instalação e uso

Bloco de anotações para controle de indivíduos contumazes, de **uso interno da corporação**.
São dois programas:

| Programa | Onde roda | Arquivo |
|---|---|---|
| **Servidor** | Computador do destacamento (Ubuntu) | `servidor/keep_servidor.py` + `servidor/instalar.sh` |
| **Aplicativo** | Celular Android 7 ou mais recente | `KeepPolicial.apk` |

Os dados (fichas, fotos, passagens) ficam **somente no computador do destacamento**.
O celular não grava fichas nem fotos: ele consulta o servidor e guarda tudo só na memória.

---

## 1. Servidor (computador do quartel, Ubuntu)

### Instalação

1. Copie a pasta `servidor` para o computador (pendrive, por exemplo).
2. Abra o **Terminal** dentro dela e rode:

```bash
chmod +x instalar.sh
```

```bash
sudo ./instalar.sh
```

3. O instalador pergunta o **nome do destacamento** e cria o **usuário administrador** (comandante).
4. No final ele mostra os **endereços** do computador e o **código de verificação** (ex.: `B2F8-63FB-20AD-F094`).

O servidor passa a iniciar sozinho sempre que o computador ligar.

> Recomendações: deixe o computador com **IP fixo** na rede do quartel (configure no roteador)
> e ative a **criptografia de disco** (LUKS) do Ubuntu, que protege os dados se o computador for furtado.

### Painel do administrador (interface gráfica)

No menu de aplicativos do Ubuntu, abra **Keep Policial - Painel do administrador**.
Ele abre no navegador, no endereço `http://127.0.0.1:8480`, e entra com o login e a senha do comandante.

O painel **só funciona no próprio computador do servidor**: ele não fica acessível pela rede nem pela internet.

No painel o administrador pode:

- **cadastrar policiais** e gerar senhas;
- **trocar login, nome, perfil e senha** de qualquer usuário, inclusive a própria. Trocar a senha encerra as sessões abertas nos celulares;
- **desativar, reativar ou desbloquear** usuários (desbloquear libera quem errou a senha 5 vezes);
- ver, para cada policial, o texto exato que ele digita no app (`login@endereço`);
- ver a **auditoria** e filtrá-la por usuário;
- publicar **avisos** que aparecem como pop-up no app (veja "Avisos do comando");
- **atualizar o servidor** pela página **Atualizações** (veja "Atualização do servidor");
- mudar o **nome do destacamento**, o **endereço pela internet** e a **duração da sessão** (padrão: 10 horas).

O painel não deixa remover, desativar ou rebaixar o **último administrador ativo**.

### Atualização do servidor

O servidor consulta as [Releases do GitHub](https://github.com/OpCer71-sketch/keep-policial/releases) **ao iniciar** e depois **uma vez por dia**.
Quando há versão nova, ele baixa o arquivo e confere a **assinatura digital da release**, o SHA-256 e a sintaxe. Release sem a assinatura do mantenedor é **recusada**: mesmo que alguém invada a conta do GitHub, nenhum código falso é instalado. Em seguida, o painel mostra o aviso **Nova versão do servidor baixada** com o botão **Instalar agora**.
Nada é instalado sem esse clique do administrador.

Ao clicar, o serviço `keeppolicial-atualizar` (root) faz a instalação:

1. troca o programa e guarda a versão anterior em `/opt/keeppolicial/keep_servidor.py.anterior`;
2. reinicia o servidor, que fica alguns segundos fora do ar;
3. se a versão nova não iniciar, **volta sozinho para a anterior**.

O resultado aparece em **Atualizações** no painel. Pelo terminal:

```bash
sudo keeppolicial-atualizar
```

> **Vindo da versão 2.0:** rode `sudo ./instalar.sh` uma vez com a pasta `servidor` da versão 3.0.
> Isso instala o serviço de atualização. As próximas versões chegam pelo painel. Os dados e usuários são mantidos.

### Falhas e suporte (versão 3.1)

O servidor anota erros dele e do aplicativo (travamentos, falha de backup, de atualização etc.) em **Falhas** no painel, **sem CPF, senhas, nomes de fichas, IDs ou endereços**.
Quando algo der errado: abra **Falhas**, clique em **Copiar relatório** e cole na conversa de suporte. Pelo terminal: `sudo keeppolicial falhas`.

A página inicial do painel também avisa sozinha quando: o **endereço pela internet** está vazio ou é um IP; o **backup** está atrasado; ou há celulares com **app desatualizado**.

### Backup automático (versão 3.1)

O servidor faz um backup por dia, confere se o banco está íntegro e guarda os **14 últimos** em `/var/lib/keeppolicial/backups`.
Esses backups ficam no mesmo computador: copie de vez em quando para um pendrive com `sudo keeppolicial backup /media/$USER/PENDRIVE/backup.tar.gz`.

### Menu no terminal (alternativa)

```bash
sudo keeppolicial
```

Também há comandos diretos, por exemplo:

```bash
sudo keeppolicial usuario adicionar sd.silva --nome "Sd PM Silva"
```

```bash
sudo keeppolicial usuario renomear sd.silva sd.silva2
```

```bash
sudo keeppolicial backup
```

### Regras de acesso

- **Policial (usuário comum):** consulta, cria e edita fichas e fotos.
- **Administrador (comandante):** tudo isso e também **exclui fichas**.
- Senhas têm no mínimo 8 caracteres e ficam gravadas criptografadas (PBKDF2).
- 5 senhas erradas seguidas bloqueiam o usuário por 15 minutos.
- Uma mesma conexão que erra a senha 20 vezes em 15 minutos, em qualquer usuário, também é bloqueada. Isso protege o servidor quando ele está acessível pela internet.
- A **auditoria** registra login, aceite do termo, cada ficha aberta, criada, editada ou excluída e as ações do painel, com usuário, IP, data e hora.
- Os backups ficam em `/var/lib/keeppolicial/backups/`. Guarde-os em mídia protegida.

Para ver a situação do serviço: `systemctl status keeppolicial`. Para reiniciá-lo: `sudo systemctl restart keeppolicial`.

---

## 2. Acesso pela internet (patrulhamento, 4G, qualquer rede)

O app funciona em qualquer rede, desde que o celular consiga **chegar ao computador do servidor**.
Dentro do quartel isso acontece pelo Wi-Fi. Fora dele é preciso uma das duas opções abaixo, configurada **uma vez** pelo comandante.

> Antes de expor o servidor, confirme com o setor de tecnologia da corporação que o uso é permitido.

### Opção A — Tailscale Funnel (recomendada: nada a instalar nos celulares)

O Tailscale fica **só no computador do servidor** e publica a porta 8443 num endereço fixo da internet
(ex.: `servidor-pm.tail1234.ts.net`). Funciona com qualquer Wi-Fi e com dados móveis, mesmo que a internet do
quartel não tenha IP público.

O Funnel só repassa a conexão, sem abri-la: a criptografia continua sendo a do próprio servidor, de ponta a ponta,
e o código de verificação continua valendo. O servidor recebe do Tailscale o IP real de cada celular, para a
auditoria e o bloqueio de senhas erradas.

1. Crie uma conta gratuita em tailscale.com.
2. Instale o Tailscale no computador do servidor e entre na conta:

   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   ```

   ```bash
   sudo tailscale up
   ```

   (No Windows, para testes: instale o Tailscale pelo site e entre na conta.)
3. No painel do Tailscale (login.tailscale.com), em **DNS**, ative **MagicDNS** e **HTTPS Certificates**.
4. Publique a porta do Keep Policial. O primeiro uso mostra um link para autorizar o Funnel na conta:

   ```bash
   sudo tailscale funnel --bg --proxy-protocol=2 --tcp=8443 tcp://127.0.0.1:8443
   ```

   Para ver o endereço publicado: `tailscale funnel status`. Para desligar, repita o comando sem o `--bg` e com a palavra `off` no final. No Windows, rode o mesmo comando sem o `sudo`, num terminal aberto como administrador.
5. No painel do Keep Policial, em **Configurações > Endereço pela internet**, informe o nome mostrado (ex.: `servidor-pm.tail1234.ts.net`).
6. O policial entra com `sd.silva@servidor-pm.tail1234.ts.net`. Esse endereço funciona em qualquer rede, inclusive no Wi-Fi do quartel.

Quem já usa o endereço da rede local não precisa mudar nada: no próximo login o app aprende o endereço pela
internet e passa a usá-lo sozinho quando o celular sair do Wi-Fi do quartel.

Nesta opção o servidor fica alcançável pela internet (como na opção B). A conexão é criptografada, há bloqueio
de senhas erradas por usuário e por celular, e tudo fica na auditoria.

### Opção A2 — Tailscale também nos celulares (rede totalmente fechada)

Se a corporação não permitir o servidor alcançável pela internet, não use o Funnel: instale o app **Tailscale**
em cada celular, na mesma conta, e use o endereço `100.x.y.z` (comando `tailscale ip -4` no servidor).
Assim só os aparelhos convidados enxergam o servidor.

### Opção B — Redirecionamento de porta no roteador + DuckDNS

Use esta opção só se a internet do quartel tiver **IP público**. Muitas operadoras usam CGNAT, e com CGNAT ela não funciona.

1. No roteador, redirecione a porta **TCP 8443** para o IP fixo do computador do servidor.
2. Crie um nome gratuito em duckdns.org (ex.: `pm-cidade.duckdns.org`) e configure a atualização automática no roteador ou no computador.
3. Informe esse nome em **Configurações > Endereço pela internet** no painel.
4. O policial entra no app com `sd.silva@pm-cidade.duckdns.org`.

Nesta opção o servidor fica visível na internet. A conexão continua criptografada e há o bloqueio de senhas erradas, mas a opção A é mais segura.

Nas duas opções o mesmo celular pode usar o endereço local no quartel e o da internet na rua.
O app reconhece que é o mesmo servidor (mesmo certificado) e não pede o código de novo.

---

## 3. Aplicativo (celular Android)

### Instalação

1. Envie o `KeepPolicial.apk` para o celular e abra o arquivo.
2. Se o Android pedir, permita instalar **desta fonte**. Se o Play Protect avisar, toque em **Mais detalhes > Instalar mesmo assim** (o app não está na Play Store).

Se o app antigo estiver instalado, basta instalar por cima.

Pelo cabo USB (com depuração USB ativa):

```bash
& "C:\Users\DRPolicena\android-tools\sdk\platform-tools\adb.exe" install -r "C:\Users\DRPolicena\OneDrive\Documentos\IA\KeepPolicial\KeepPolicial.apk"
```

### Entrar (como num e-mail)

1. No campo **Usuário**, digite `login@endereço`, como no exemplo `sd.silva@192.168.0.10` ou `sd.silva@servidor-pm.tail1234.ts.net`. O comandante informa os dois (o painel mostra o texto pronto).
2. Digite a **senha**, **leia e aceite o Termo de Compromisso de Sigilo** e toque em **Entrar**.
3. **Somente no primeiro acesso** a um servidor, o app mostra um **código de verificação**. Confira se ele é igual ao do painel do servidor e toque em **É igual, entrar**.
   Isso garante que ninguém se passe pelo servidor do quartel. Depois disso o app lembra o servidor e basta usuário e senha.

Nos próximos acessos o app já sugere o último `login@endereço`. A conexão é **sempre** criptografada (HTTPS), mas isso não aparece para o policial.

Para usar outro destacamento, digite o outro `login@endereço`. Cada servidor tem login e dados independentes.
O link **Servidores salvos** permite remover um servidor do celular.

### Fichas

- A tela inicial mostra as fichas em cartões (como um bloco de notas). A faixa colorida indica o comportamento:
  verde = obediente, laranja = resistente passivo, vermelho = resistente ativo.
- A busca procura por nome, alcunha, CPF, RG, nome da mãe ou nº do REDS.
- **+ Nova ficha** abre os campos:
  - até **10 fotos** (câmera ou galeria). Toque para ampliar, toque e segure para remover;
  - nome completo, alcunha, CPF (com conferência dos dígitos), RG, endereço;
  - filiação: pai e mãe;
  - comportamento: obediente, resistente passivo ou resistente ativo;
  - passagens pela polícia: nº do REDS, data e natureza/histórico (quantas forem necessárias);
  - veículos: marca, modelo, cor e placa (até 20). Marca e cor têm sugestões; a placa é conferida no padrão antigo ou Mercosul;
  - observações: até 200 caracteres.
- A busca também encontra pela placa (com ou sem traço) e pelo modelo do veículo.

### Importar fichas (somente administrador)

Menu > **Importar fichas (planilha ou JSON)**. Aceita `.xlsx`, `.csv` e `.json` (modelos em `servidor dst pm/modelos de importacao`).
Na planilha, a 1ª linha tem os títulos: nome, alcunha, cpf, rg, endereco, pai, mae, comportamento, observacoes, reds, data, natureza, marca, modelo, cor, placa.
Linhas sem nome logo abaixo de uma ficha (ou com o mesmo CPF) acrescentam passagens e veículos a ela.
Quem já está cadastrado (mesmo CPF, ou mesmo nome e mãe) não é duplicado: o arquivo só completa campos vazios e acrescenta passagens e veículos novos.

### Versão

Versão atual: **3.0** (servidor 3.0.0, app 3.0). Ela aparece no fim do menu do app e no topo do painel. Atualize primeiro o servidor e depois os celulares.

### Atualização automática do app

Na **primeira abertura do dia**, o app consulta a última versão publicada em
[Releases](https://github.com/OpCer71-sketch/keep-policial/releases) no GitHub.
Se houver versão nova, ele **baixa o APK sozinho** e mostra a janela **Atualização disponível**.
Para instalar, toque em **Instalar** e confirme na tela do Android.

- Na primeira vez, o Android pede para **permitir que o Keep Policial instale apps** ("Permitir desta fonte"). Ative a opção e volte ao app.
- **Depois** adia o aviso até o dia seguinte. Para instalar antes, use **menu > Verificar atualização do app**.
- O app confere o arquivo baixado (SHA-256 da release, nome do pacote e número da versão).
  O Android também só instala por cima se o APK tiver a **mesma assinatura** da versão instalada.
- As fichas ficam no servidor, então atualizar o app não apaga nada.

### Avisos do comando (pop-up)

No painel do administrador, em **Avisos**, o comandante escreve um título e uma mensagem e clica em **Publicar aviso**.
Ao abrir o app, cada policial vê o aviso numa janela e precisa tocar em **Ciente**.

- A página **Avisos** mostra quem já deu ciência e quando. A ciência também fica registrada na auditoria (`CIENTE_AVISO`).
- Cada policial vê cada aviso **uma vez**, até tocar em Ciente.
- **Encerrar** tira o aviso do ar para quem ainda não o viu. **Reativar** o coloca de volta.

### Novidades da versão 3.2

- **Visual tático:** cinza chumbo, preto fosco e verde oliva/coyote; botões e campos maiores para uso em campo.
  Nos cartões, a **borda e a tarja** mostram a periculosidade: **vermelho** = resistente ativo, **amarelo** = resistente passivo, **verde** = obediente. Aparecem também o status e o selo **CNH VENCIDA**.
- **Modo escuro:** menu > **Aparência** (automático, claro ou escuro). O escuro usa cinzas profundos e preto para preservar a visão noturna.
- **Funciona sem internet:** consultar, buscar, criar e editar fichas funciona offline. O que for feito fica numa fila e é enviado sozinho quando a conexão volta. Uma faixa no topo mostra **OFFLINE** e quantas alterações aguardam envio.
  - Para entrar **sem internet** é preciso já ter entrado antes **com internet** neste celular, e o último acesso com internet deve ter menos de 72 horas.
  - Se a mesma ficha foi alterada por outra pessoa enquanto você estava offline, **vale a edição que chegar por último**; a versão anterior fica no histórico da ficha.
  - Alterações que o servidor recusar (ex.: dado inválido) **não somem**: ficam marcadas na faixa vermelha para você corrigir ou descartar.
- **CNH na ficha:** nº (11 dígitos, confere os dígitos verificadores), categoria (A, B, C, D, E, AB, AC, AD, AE) e validade, com alerta de **vencida** (vermelho) ou **a vencer em 30 dias** (âmbar).
- **Status operacional:** em acompanhamento, preso, foragido ou inativo.
- **Filtros avançados** (botão ao lado da busca): periculosidade, status, categoria da CNH, CNH vencida, com veículo, período de cadastro/atualização, envio pendente e ordenação.
- **Histórico de auditoria** em cada ficha: **quem**, **o que** (criação, consulta, campos alterados), **quando** e de **qual aparelho**. No servidor o registro é **imutável** (o banco recusa alterar ou apagar), inclusive as consultas feitas offline, que chegam na sincronização.
- **Escanear documento/placa (RG, CPF, CNH, placa):** o botão só aparece quando o motor de leitura está incluído no app.

> **Ordem de atualização (importante):** atualize **primeiro o servidor** (painel > Atualizações > Instalar agora) e **depois** os celulares. O app 3.2 só funciona com servidor 3.2: com servidor antigo ele mostra a faixa vermelha "Servidor desatualizado" e não envia nem baixa nada (o que foi feito fica guardado no aparelho). O app também **não instala a atualização sozinho** enquanto o servidor do destacamento estiver numa versão anterior.

### Sessão e segurança no celular

- A sessão dura **10 horas** (um turno), mesmo com o app em segundo plano ou fechado pelo Android. O menu mostra até que horas ela vale.
  O botão Voltar só minimiza o app. Para encerrar antes, use **menu > Sair**.
- A sessão fica guardada **cifrada** com uma chave do Android Keystore. Como quem pegar o celular desbloqueado durante o turno consegue abrir o app, mantenha o **bloqueio de tela** do celular ativo.
- **Print de tela é permitido.** Prints ficam na galeria do celular: trate-os como material sigiloso e apague-os quando não forem mais necessários.
- **Modo offline (3.2):** o app guarda uma cópia das fichas **cifrada** (AES-256, chave do Android Keystore, que não sai do aparelho). Sem a senha e sem este celular, os arquivos não revelam nada. As fotos tiradas pela câmera não passam pela galeria.
- **Perdeu o celular?** Avise o comandante e **desative o policial no painel**: o servidor deixa de aceitar o login e a cópia do aparelho só abre com a senha do policial, por no máximo **72 horas** desde o último acesso com internet.
- As fotos têm a localização (GPS) e os outros metadados removidos antes do envio.

---

## Recompilar o aplicativo

No Linux, com as ferramentas em `~/android-tools` (JDK 17, Android SDK 34 e Gradle 8.7):

```bash
cd android && ANDROID_HOME=~/android-tools/sdk ~/android-tools/gradle-8.7/bin/gradle assembleRelease
```

O APK sai em `~/.keeppolicial-build/app/outputs/apk/release/app-release.apk`.

### Chave de assinatura (MUITO IMPORTANTE)

A partir da 3.0 o app é assinado com a **chave definitiva** do Keep Policial. Ela fica fora do repositório, em `~/.keeppolicial/`:

- `keeppolicial-release.jks`: a chave;
- `assinatura.properties`: a senha e o caminho da chave.

O Android só aceita uma atualização se o APK novo tiver a **mesma assinatura** do instalado.
Se essa chave se perder, todos os celulares terão de desinstalar e reinstalar o app.
**Guarde cópias da pasta `~/.keeppolicial`** em dois lugares seguros, como um pendrive guardado com o comandante e outra mídia. Não publique essa pasta.

Para compilar em outro computador, copie a pasta para `~/.keeppolicial/` e ajuste o caminho `storeFile` no `.properties`.
Também é possível apontar outro arquivo com a variável `KEEP_ASSINATURA`.

> **Passagem da 2.0 para a 3.0:** a chave que assinou a 2.0 se perdeu com o computador antigo.
> Por isso, **uma única vez**, cada policial precisa desinstalar a 2.0 e instalar a 3.0.
> As fichas ficam no servidor e não se perdem. No primeiro acesso, ele digita `login@endereço` de novo e confere o código de verificação.
> Da 3.0 em diante, as atualizações chegam sozinhas.

## Publicar uma nova versão (Releases)

O app e o servidor procuram atualização na **última release** do repositório. Cada release deve ter:

| Arquivo | Para quê |
|---|---|
| `KeepPolicial.apk` | atualização do app |
| `keep_servidor.py` | atualização do servidor |
| `SHA256SUMS` | conferência dos arquivos |
| `SHA256SUMS.sig` | **assinatura** do SHA256SUMS (sem ela os servidores recusam a atualização) |

O `publicar-release.sh` assina sozinho com a chave `~/.keeppolicial/release-signing.pem` (fica só com o mantenedor; **faça cópia dela junto da chave do app**). A chave pública correspondente está em `servidor/chave-publica-release.pem` e dentro do `keep_servidor.py`.

Antes de publicar, aumente a versão em dois lugares:

- **app:** `versionCode` e `versionName` em `android/app/build.gradle`;
- **servidor:** `VERSAO` em `servidor/keep_servidor.py`.

A tag da release segue o formato `v3.1`. Depois, publique com o script (precisa do `gh` autenticado):

```bash
./publicar-release.sh v3.1 caminho/para/app-release.apk
```
