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
- mudar o **nome do destacamento**, o **endereço pela internet** e a **duração da sessão** (padrão: 10 horas).

O painel não deixa remover, desativar ou rebaixar o **último administrador ativo**.

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

Versão atual: **2.0** (servidor 2.0.0, app 2.0). Ela aparece no fim do menu do app e no topo do painel. Atualize primeiro o servidor e depois os celulares.

### Sessão e segurança no celular

- A sessão dura **10 horas** (um turno), mesmo com o app em segundo plano ou fechado pelo Android. O menu mostra até que horas ela vale.
  O botão Voltar só minimiza o app. Para encerrar antes, use **menu > Sair**.
- A sessão fica guardada **cifrada** com uma chave do Android Keystore. Como quem pegar o celular desbloqueado durante o turno consegue abrir o app, mantenha o **bloqueio de tela** do celular ativo.
- **Print de tela é permitido.** Prints ficam na galeria do celular: trate-os como material sigiloso e apague-os quando não forem mais necessários.
- Nenhuma ficha ou foto é gravada pelo app. As fotos tiradas pela câmera vão direto para o servidor, sem passar pela galeria.
- As fotos têm a localização (GPS) e os outros metadados removidos antes do envio.

---

## Recompilar o aplicativo

```bash
$env:JAVA_HOME="C:\Users\DRPolicena\android-tools\jdk-17.0.20.1+1"; cd "C:\Users\DRPolicena\OneDrive\Documentos\IA\KeepPolicial\android"; & C:\Users\DRPolicena\android-tools\gradle-8.7\bin\gradle.bat --offline assembleRelease
```

O APK sai em `%USERPROFILE%\.keeppolicial-build\app\outputs\apk\release\app-release.apk`
(fora do OneDrive, que trava arquivos durante a compilação).
