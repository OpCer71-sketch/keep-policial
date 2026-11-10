# Como atualizar o servidor do Keep Policial para a versão 3.0

Passo a passo para quem **não tem experiência com computador**. Siga na ordem, sem pular etapas.
Leva uns **15 minutos**. Durante a troca, o aplicativo fica fora do ar por **menos de 1 minuto**.

> **Nada se perde.** As fichas, fotos, policiais cadastrados e senhas continuam iguais.
> Mesmo assim, o passo 3 faz uma cópia de segurança antes de tudo.

---

## O que você vai precisar

- O **computador do servidor** (o do quartel, com Ubuntu), ligado.
- A **senha de entrada desse computador** (a que se digita para ligar o Ubuntu).
- Um **pendrive** com a pasta **`servidor`** da versão 3.0 (veja o passo 1).
- Um **segundo pendrive**, ou o mesmo, para guardar a cópia de segurança.

---

## Passo 1 — Baixar o pacote da versão 3.0

Num computador com internet:

1. Clique neste link para baixar o pacote completo:
   **https://github.com/OpCer71-sketch/keep-policial/releases/latest/download/KeepPolicial-instalacao.zip**
2. O arquivo **`KeepPolicial-instalacao.zip`** vai para a pasta **Downloads**.
3. Copie esse arquivo para o pendrive.

Dentro dele está a pasta **`KeepPolicial-3.0`**, com:

- `servidor` (o programa do computador do quartel);
- `KeepPolicial.apk` (o aplicativo dos celulares);
- `LEIA-ME PRIMEIRO.txt` e os manuais.

---

## Passo 2 — Abrir o pacote no computador do servidor

1. Coloque o pendrive no computador do servidor.
2. Abra o pendrive em **Arquivos** (clique no nome dele na lateral esquerda).
3. Clique com o **botão direito** em `KeepPolicial-instalacao.zip` e escolha **Extrair para...** ou **Extrair aqui**.
4. Copie a pasta **`KeepPolicial-3.0`** que apareceu.
5. Na lateral esquerda, clique em **Pasta pessoal** (o ícone de casinha), clique com o botão direito num espaço vazio e escolha **Colar**.

Agora existe a pasta `KeepPolicial-3.0` dentro da sua Pasta pessoal.

---

## Passo 3 — Fazer a cópia de segurança (não pule)

1. Abra o **Terminal**: aperte juntas as teclas **Ctrl + Alt + T**. Abre uma janela preta ou roxa onde se digitam comandos.
2. Digite o comando abaixo e aperte **Enter**:

   ```bash
   sudo keeppolicial backup
   ```

3. Ele pede a **senha do computador**. Digite e aperte **Enter**.
   **Ao digitar a senha não aparece nada na tela, nem bolinhas.** Isso é normal: digite assim mesmo.
4. Ao terminar, aparece a mensagem `Backup salvo em: /var/lib/keeppolicial/backups/...`. Pronto, a cópia está feita.

> Se aparecer `comando não encontrado`, o servidor não foi instalado do jeito padrão.
> **Pare aqui** e chame quem cuida do sistema.

---

## Passo 4 — Instalar a versão 3.0

Ainda no Terminal, digite **um comando de cada vez**, apertando **Enter** depois de cada um.

1. Entrar na pasta:

   ```bash
   cd ~/KeepPolicial-3.0/servidor
   ```

2. Permitir que o instalador rode:

   ```bash
   chmod +x instalar.sh atualizar-servidor.sh
   ```

3. Rodar o instalador:

   ```bash
   sudo ./instalar.sh
   ```

   Se pedir a senha do computador, digite como no passo 3.

Aguarde. Vão passar várias linhas na tela. Se o instalador fizer alguma pergunta, siga a tabela:

| Se aparecer... | Faça isto |
|---|---|
| `Nome do destacamento` | Isso **não deveria** aparecer numa atualização. Se aparecer, digite o nome do destacamento e aperte Enter. |
| `Login do administrador` | Também **não deveria** aparecer. Se aparecer, **pare** (feche o Terminal) e chame quem cuida do sistema. |
| `Certificado já existe` | **Normal.** O código de verificação continua o mesmo. |

No final aparece um quadro parecido com este:

```
===================================================================
 Servidor instalado e em execução.
 ...
 Atualizar o servidor:    painel > Atualizações  ou  sudo keeppolicial-atualizar
===================================================================
```

Se apareceu a linha **"Atualizar o servidor"**, a instalação deu certo. 🎉

---

## Passo 5 — Conferir se está tudo certo

1. Abra o **Painel do administrador**: menu de aplicativos → **Keep Policial - Painel do administrador**.
   Se o painel já estava aberto no navegador, aperte **F5** para recarregar.
2. Entre com o login e a senha do comandante.
3. No topo da página deve aparecer **"versão 3.0"**.
4. No menu de cima devem aparecer dois itens novos: **Avisos** e **Atualizações**.
5. Clique em **Atualizações** e depois em **Verificar agora**. Deve aparecer *"O servidor já está na versão mais recente"*.

Se tudo isso aparecer, **o servidor está atualizado**. Pode fechar o Terminal e tirar o pendrive.

---

## Passo 6 — Guardar a cópia de segurança

A cópia do passo 3 fica numa pasta protegida do computador. Para levá-la ao pendrive:

1. No Terminal, digite **(troque `SEU-PENDRIVE` pelo nome do pendrive, como aparece em Arquivos)**:

   ```bash
   sudo sh -c "cp /var/lib/keeppolicial/backups/*.tar.gz /media/$USER/SEU-PENDRIVE/"
   ```

2. Guarde o pendrive em **local seguro**. Ele contém **dados sigilosos** de todas as fichas.

---

## Depois disso: os celulares

A versão 3.0 do aplicativo precisa ser instalada **à mão, uma única vez**, em cada celular:

1. **Desinstale** o Keep Policial antigo (2.0) do celular.
2. Instale o **KeepPolicial.apk** (está no pacote).
   Ele está em [Releases](https://github.com/OpCer71-sketch/keep-policial/releases), ou peça o arquivo a quem cuida do sistema.
3. Entre normalmente com **`login@endereço`** e a senha.
4. No primeiro acesso, o app mostra o **código de verificação**. Confira se é igual ao que aparece no painel e confirme.

As fichas **não se perdem**, porque ficam no servidor.

---

## As próximas atualizações serão automáticas

A partir da 3.0 **não é preciso repetir este tutorial**.

- **Servidor:** quando sair uma versão nova, o painel do administrador mostra uma faixa amarela **"Nova versão do servidor baixada"**.
  Basta clicar em **Instalar agora**. Se algo der errado, o sistema **volta sozinho** para a versão anterior.
- **Celulares:** na primeira vez que o app é aberto no dia, ele avisa **"Atualização disponível"**. Basta tocar em **Instalar**.

---

## Deu problema?

| Problema | O que fazer |
|---|---|
| `Permissão negada` ao rodar o instalador | Confira se digitou o `sudo` no começo do comando. |
| `Arquivo ou diretório inexistente` | A pasta `KeepPolicial-3.0` não está na Pasta pessoal. Refaça o passo 2. |
| No 4G o app não conecta, mas no Wi-Fi do quartel funciona | No painel, vá em **Configurações** e confira o campo **Endereço pela internet**. Ele deve ter o nome terminado em `.ts.net` (ex.: `pc-o1.tail733bae.ts.net`). Se estiver vazio, rode `sudo bash ligar-internet.sh` na pasta `servidor`. No celular, use `login@nome.ts.net`, e não o número `192.168...`. |
| A senha não é aceita | Digite com calma (ela não aparece na tela) e confira o Caps Lock. |
| O painel não abre | No Terminal, rode `sudo systemctl restart keeppolicial`, espere 10 segundos e tente de novo. |
| O app no celular diz que não conecta | Confira se o computador do servidor está ligado e conectado à internet. |
| Qualquer outra mensagem de erro | **Tire uma foto da tela** e mande para quem cuida do sistema. Não apague nada. |
