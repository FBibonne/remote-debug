# Débogage d'une JVM distante avec IntelliJ _via_ un tunnel SSH

Ce billet présente une experimentation d'exécution en debug d'une application java tournant sur une VM distante à l'aide d'un tunnel SSH.

## _<<Débogage d'une JVM distante>>_ : de quoi parle-t-on ?

Lorsqu'une application dysfonctionne (erreur explicite ou résultat inattendu), on peut utiliser plusieurs outils ou méthodes
pour comprendre ce qui ne va pas et modifier le code en conséquence :
- lire les logs
- exécuter /écrire des tests spécifiques
- consulter des rapports d'observabilité
- regarder les données en base, dans les fichiers, ...
- exécuter en debug

L'exécution en debug d'une application est bien connu sur le poste du développeur pour exécuter le code pas à pas et comprendre
ce qui ne va pas quand les autres outils n'ont pas permis de fournir une explication. Ceci dit, cela arrive parfois avec
java, l'application ne se comporte pas de la même manière sur tous les environnements, toutes choses égales par ailleurs. Ça 
a par exemple été [le cas avec l'encodage par défaut jusqu'en java 18](https://openjdk.org/jeps/400). On peut donc se retrouver à chercher la cause
d'un dysfonctionnement qui ne produit que sur un environnement distant donné : comment alors exécuter en debug (pas à pas)
depui l'IDE sur notre poste l'application qui tourne sur une JVM sur un serveur distant ?

Ce qui se résume par le schéma suivant :

```mermaid
graph LR
    subgraph "Poste de travail développeur" 
        subgraph IDE 
           Debogueur 
        end
    end
   subgraph "Serveur distant" 
       subgraph "JVM distante" 
           Application
       end
   end
   Debogueur --"commande l'exécution pas à pas"--> Application

```

## Pourquoi un tunnel SSH ?

La communication entre le Debogueur dans l'IDE et la JVM distante qui héberge l'application se fait via des échanges réseau 
TCP. Le port d'écoute de la JVM est paramétrable dans la commande de lancement de l'application (cf. plus bas). Le port utilisé 
par le débogueur de l'IDE est choisi aléatoirement pas ce dernier.

Si le débogueur de votre IDE arrive directement à joindre la VM distant sur le port paramétré (en suivant le tutoriel jetBrains
figurant dans [les références ci-dessous](#références)), c'est que les ports nécessaires sont ouverts et il n'est pas nécessaire 
de suivre plus loin ce tutoriel, sauf si le contexte réseau n'est pas sûr (cf. [Précautions](#précautions)).

Si en revanche, comme dans beaucoup d'entreprises, seuls certains ports (déjà utilisés) sont ouverts sur les machines, le débogage 
à distance ne fonctionnera pas directement et il sera nécessaire de passer par un tunnel SSH pour les communications entre le 
débogueur et la JVM distante. C'est l'objet de ce qui suit.

## Architecture avec le tunnel SSH

Ici c'est [un tunnel SSH avec redirection de port local](https://blog.stephane-robert.info/docs/admin-serveurs/linux/ssh-tunneling/#redirection-de-port-local) qui sera utilisé.
Ce tunnel permettra d'encapsuler le traffic réseau TCP entre le débogueur de l'IDE et la JVM distante au sein des communications SSH entre les deux machines (puisqu'ici on
suppose qu'elles sont premises). En pratique, le port sur lequel la JVM écoute les instructions du débogueur apparaîtra comme un port
local de la machine et sera utilisé en tant que tel par le débogueur de l'IDE

## Mise en œuvre sur un exemple

Dans l'exemple qui suit, l'utilisateur `fabrice` souhaite déboguer à distance depuis son IDE IntelliJ une [application java](#application-test) qui
tourne sur une machine distante identifiée par son IP `192.168.0.82`. La JVM écoutera les instructions du débogueur sur le port 5005 qui sera
"relié par le tunnel SSH" au port 50005 de la machine locale.

![Archi debogueur avec tunnel SSH](./doc/debogueurTunnelSSH.png)

### Prérequis

- l'utlisateur `fabrice` doit pouvoir effectuer une connexion ssh sur la machine `192.168.0.82` : `ssh fabrice@192.168.0.82`
- sur la machine distante, l'utilisateur doit pouvoir relancer l'application test java en modifiant sa ligne de commande

### Application test

L'application Test est une simple application java web qui s'appuie sur Spring Boot et qui tient une classe :

```java
@SpringBootApplication
public class RemoteDebugApplication {

	public static void main(String[] args) {
		SpringApplication.run(RemoteDebugApplication.class, args);
	}

	@Controller
    record DebugedController(SimpleAsyncTaskExecutor executor) {

		public DebugedController() {
			this(new SimpleAsyncTaskExecutor());
			executor.setVirtualThreads(true);
		}

		@GetMapping("/test")
		ResponseBodyEmitter test(){
			ResponseBodyEmitter emitter = new ResponseBodyEmitter();
			executor.execute(new RunnableEmmiter(emitter));
			return emitter;
		}
	}

	static class RunnableEmmiter implements Runnable{

		public static final Duration PAUSE_TIME = Duration.ofMillis(50);
		private final ResponseBodyEmitter emitter;
		private boolean stop = false;

        RunnableEmmiter(ResponseBodyEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
		public void run() {
			while (!stop) {
                try {
                    emitter.send(LocalDateTime.now());
                    emitter.send("\n");
                    Thread.sleep(PAUSE_TIME);
                } catch (InterruptedException | IOException e) {
                    stop = true;
                }
            }
			emitter.complete();
		}
	}
}
```

L'application hérite du pom parent de Spring Boot (`org.springframework.boot:spring-boot-starter-parent`) et contient une unique dépendance sur 
`org.springframework.boot:spring-boot-starter-web`.

Elle expose un seul endpoint `GET /test` accessible par tous et qui sert indéfiniment des timestamps au client jusqu'à ce que l'application soit
arrêté ou que le processus soit interrompu (permet de vérifier qu'on arrive bien à modifier la valeur du bouléen par le débogueur à distance).

### Vérification en local

Il s'agit de lancer l'application en local (celle-ci aura le même comportement en local qu'à distance) en mode debug dans l'IDE et de mettre un point
d'arrêt à la ligne 51 sur `emitter.send(LocalDateTime.now());`. Par défaut l'application écoute sur le port 8080, on peut donc déclencher l'affichage 
des timestamps en appelant `GET http://localhost:8080/test` avec un client type curl. La vue de débogage se met en place et l'exécution se bloque sur
le point d'arrêt

![Vue débogage en local](./doc/debugLocal.png)

On vérifie que le pas à pas fonctionne et qu'on tourne infiniment dans la boucle en faisant _Step over_ tandis que côté client on reçoit un nouveau
timsestamp à chaque itération. On peut vérifier également qu'on modifiant la valeur du bouléen `stop` en la passant à `true` via le débogueur puis
en reprenant l'exécution, le programme quitte la boucle et la réponse http est clôturée.

### Déploiement de l'application sur le serveur distant

L'application sera exécutée en tant que _fat jar_ exécutable : ce jar est produit automatiquement par maven ou gradle lors du build de l'exécution de
la phase _package_ (resp. la tâche _bootJar_). C'est ce jar qui doit être déposé sur la VM distante `192.168.0.82` pour y être exécuté.

### Création de la "configuration de débogage dans IntelliJ"

Du point de vue du débogueur Intellij, l'application distante sera sur localhost et écoutera les instructions du débogueur
sur le port 50005 : l'exécution "Remote JVM Debug" devra être configurée en tant que tel.

- Dans Intellij, ouvrir la fenêtre avec les configuration d'exécution (Menu -> Run -> Edit configurations ...)
- Créer une nouvelle configuration de type _Remote JVM Debug_
- 

### Lancement de l'application sur le serveur distant

### Création du tunnel SSH

### Lancement du débogueur dans Intellij

### Ca marche !

### Arrêter dans l'ordre

## Précautions

## Sur kubernetes

Si l'application est déployée dans un cluster kubernetes, on utilisera le [_port forwarding_](https://kubernetes.io/docs/reference/generated/kubectl/kubectl-commands#port-forward) 
plutôt que le _tunneling SSH_.
Si l'outil [telepresence](https://www.getambassador.io/products/telepresence) est présent sur le cluster,
[Intellij s'intègre aussi avec](https://www.jetbrains.com/help/idea/telepresence.html).

## Alternatives au débogage distant

- Utiliser un enregistrement [_JDK Flight Recorder_](https://dev.java/learn/jvm/jfr/intro/) pour observer ce qui se passe 
sur une JVM distante (il n'est pas nécessaire de la redémarrer). 

## Références

- [Le tunneling SSH sur le blog de Stéphane Robert](https://blog.stephane-robert.info/docs/admin-serveurs/linux/ssh-tunneling/)
- [Débogage distant avec Intellij](https://www.jetbrains.com/help/idea/tutorial-remote-debug.html)
- [Débogage en java pour débuter](https://dev.java/learn/debugging/)
- [_Java Platform Debugger Architecture (JPDA)_](https://docs.oracle.com/en/java/javase/21/docs/specs/jpda/architecture.html)