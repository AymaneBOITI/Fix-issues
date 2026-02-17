# Analyse Fortify - SQL Injection
## Projet: interface-payment-de (payments-program)

---

## CLASSES IDENTIFIÉES

### 1. SqlHandler.java
- **Package:** `com.bnpparibas.atlanticc.ipmt.sql.handler.impl`
- **Rôle:** Classe parent, gère la connexion JDBC et les PreparedStatement
- **Méthode clé:**
  ```java
  // Ligne 103-122
  public PreparedStatement prepareStatement(String query, Object[] parameters) throws SQLException {
      PreparedStatement ps;
      try {
          ps = this.liqbatchConnection.prepareStatement(query); // LIGNE 107 - Fortify flag ici
          if (parameters != null) {
              int index = 0;
              for (Object parameter : parameters) {
                  index++;
                  if (parameter == null || parameter instanceof String) {
                      ps.setString(index, (String) parameter);
                  } else if (parameter instanceof Integer) {
                      ps.setInt(index, (int) parameter);
                  } else {
                      // throw SQLException
                  }
              }
          }
      }
  }
  ```
- **Aussi:** méthode `updateQuery()` à la ligne ~132 (pas encore vue)
- **Connexion:** `liqbatchConnection`

---

### 2. PaymentSqlHandler.java
- **Package:** `com.bnpparibas.atlanticc.ipmt.sql.handler.impl`
- **Extends:** SqlHandler
- **Implements:** IPaymentSqlHandler
- **Schema:** `LIQBATCHSCHEMA = P00_ICOR_00_Config.getInstance().liqBatchSchema`

#### Constantes:
- `CUS_NME_FULL_NAME = "CUS_NME_FULL_NAME"`
- `ECD_XID_REF_NUMBER = "ECD_XID_REF_NUMBER"`
- `ENRICH_ERROR_MESSAGE_FORMAT = "Could not get %s for %s %s"`

#### Méthodes analysées:

| # | Méthode | Ligne | Pattern | Statut |
|---|---------|-------|---------|--------|
| 1 | `getBranches(String zoneName)` | ~105 | `prepareStatement(SELECT_BRANCHES, new Object[]{zoneName})` | ✅ OK |
| 2 | `getDay()` | ~130 | Pas de SQL | ✅ OK |
| 3 | `getSeqNumber()` | ~138 | `prepareStatement(SELECT_SEQUENCE, new Object[]{this.getDay()})` | ✅ OK |
| 4 | `getSeqNumber()` (insert) | ~158 | `updateQuery(INSERT_SEQUENCE, new Object[]{this.getDay(), seq})` | ✅ OK |
| 5 | **`getNewPayments(String branchCondition)`** | **~168** | **`String.format(SELECT_NEW_PAYMENTS, branchCondition), null`** | **❌ INJECTION SQL CRITIQUE** |
| 6 | `getRoles(String msgId)` | ~200 | `prepareStatement(SELECT_MSG_ROLES, {CUSTOMER_LEGAL_ADDRESS, CUSTOMER_PAY_ADDRESS, msgId})` | ✅ OK |
| 7 | `getEventIncr(String msgId)` | ~230 | `prepareStatement(SELECT_EVENT_INCREMENT, {msgId, msgId})` | ✅ OK |
| 8 | `setProcessingAreaData(Payment)` | ~246 | `prepareStatement(SELECT_PROCESSING_AREA, {pidFacility, pidDeal})` | ✅ OK |
| 9 | `getPrimayBorrowerFullNme(String pidDeal)` | ~262 | `prepareStatement(SEELCT_PRIMARY_BORROWER_NME, new Object[]{pidDeal})` | ✅ OK |
| 10 | **Ligne 603** | **~603** | **`String.format(???, branchCondition)` → `updateQuery()`** | **❌ INJECTION SQL CRITIQUE** |

---

### 3. Main.java
- **Package:** `com.bnpparibas.atlanticc.ipmt.process`
- **Champs importants:**
  - `IPaymentSqlHandler sqlHandler` (ligne 37)
  - `String branchesCondition = ""` (ligne 43)
  - `String cdeName` (ligne 44)
  - `InterfaceParameter parameter` (ligne 45)

- **Constructeur Main(args, sqlHandler, mqService):**
  - `this.cdeName = this.getCdeParam(args)` (ligne 50)
  - Crée `PaymentSqlHandler()` (ligne 54)
  - `this.parameter = this.sqlHandler.getConfiguration(cdeName)` (ligne 55)
  - `this.initProcessors(cdeName)` (ligne 66)

---

## TRACE FORTIFY (Path 1 of 3) - Flux de données
```
1. PaymentSqlHandler.java:109 → executeQuery() retourne ResultSet
2. PaymentSqlHandler.java:111 → getString() → branches.add()  
3. PaymentSqlHandler.java:121 → return branches
4. Main.java:147 → getBranches(return) → assignment to branches
5. Main.java:148 → buildBranchCondition(branches) → this.branchesCondition
6. Main.java:66 → initProcessors(this.branchesCondition)
7. Main.java:85-87 → process(this.branchesCondition)
8. Main.java:107 → loadWorkingTables(0)
9. PaymentSqlHandler.java:603 → String.format() → updateQuery()
10. SqlHandler.java:132 → prepareStatement(0)
11. SqlHandler.java:107 → prepareStatement(0) ← FORTIFY FLAG ICI
```

**Résumé du flux:**
- Les **noms de branches** viennent de la DB via `getBranches()`
- `buildBranchCondition()` construit une condition SQL avec ces branches (ex: `" AND IMT_CDE_BRANCH IN ('BR1','BR2')"`)
- Cette condition est **concaténée/formatée** directement dans les requêtes SQL
- Utilisée dans `getNewPayments()` (ligne ~170) et `updateQuery()` (ligne ~603)

---

---

### 4. PaymentSqlQueries.java
- **Classe de constantes SQL**

```java
// SEQUENCE
SELECT_SEQUENCE = "select CDE_SEQ_NUMBER from "+LIQBATCHSCHEMA+".TBP_IPMT_DAY_SEQ wh...";  // ✅ uses ?
INSERT_SEQUENCE = "insert into "+LIQBATCHSCHEMA+".TBP_IPMT_DAY_SEQ values(?,?)";  // ✅
UPDATE_SEQUENCE = "update "+LIQBATCHSCHEMA+".TBP_IPMT_DAY_SEQ set CDE_SEQ_NUMBER=? w..."; // ✅

// ❌ CRITIQUE - SELECT_NEW_PAYMENTS contient %s
SELECT_NEW_PAYMENTS = "select" +
    " REPLACE(M.IMT_AMT_OUT_TOT, '.', ',') AS IMT_AMT_OUT_TOT_FMT," +
    " TO_CHAR(M.IMT_DTE_VALUE_DATE,'RRMMDD') AS IMT_DTE_VALUE_DATE_FMT," +
    " TO_CHAR(M.IMT_DTE_BUSINESS,'RRMMDD') AS IMT_DTE_BUSINESS_FMT," +
    " D.DEA_NME_DEAL AS DEAL_NME," +
    " D.DEA_PID_DEAL AS PID_DEAL," +
    " F.FAC_NME_FACILITY AS FAC_NME," +
    " M.* from "+LIQBATCHSCHEMA+".TBP_IPMT_IMT_OUT_DIFF M " +
    "join LIQCREATOR.VLS_DEAL D on D.DEA_PID_DEAL = M.IMT_PID_DEAL " +
    "left join LIQCREATOR.VLS_FACILITY F on F.FAC_PID_FACILITY = M.IMT_PID_FACILITY " +
    "where TRIM(M.IMT_CDE_BRANCH) %s";  // ← %s = injection du branchCondition !
```

#### SELECT_MSG_ROLES (début visible) :
```java
SELECT_MSG_ROLES = "select distinct R.IOR_RID_OUTGNG_IMT," +
    " TRIM(R.IOR_CDE_SWFT_ID) as IOR_CDE_SWFT_ID," +
    " R.IOR_TXT_DESC, " +
    " NVL(TRIM(R.IOR_TXT_ACCT_NO),null) as IOR_TXT_ACCT_NO," +
    " R.IOR_CDE_SWFT_RTYP," +
    " CUS.CUS_NME_FULL_NAME," +
    " LEGAL.ADR_ADR_ZIP_CODE as ZIP_CODE," +
    " LEGAL.ADR_ADR_CITY as CITY," +
    " LEGAL.ADR_CDE_COUNTRY as COUNTRY_CODE," + ...
```

---

### 5. Main.java - Flux complet

#### `buildBranchCondition()` - **LA MÉTHODE CLÉ** :
```java
private static String buildBranchCondition(List<String> branches) {
    return " in ('" + branches.stream()
        .map(String::trim)
        .collect(joining("', '")) + "')";
}
// Produit: " in ('BR1', 'BR2', 'BR3')"
```

#### `initProcessors()` - propagation du branchesCondition :
```java
private void initProcessors(String cdeName) {
    Main.setInstance(this);
    this.sqlHandler.setAutoCommit(false);
    this.auditHandler = new AuditHandler(cdeName, this.sqlHandler);
    this.auditHandler.loadLogId();
    List<String> branches = this.sqlHandler.getBranches(cdeName);        // ligne 147
    this.branchesCondition = buildBranchCondition(branches);              // ligne 148
    this.responseProcessor = new ResponseProcessor(branchesCondition, sqlHandler, mqService, auditHandler);
    this.paymentProcessor = new PaymentProcessor(parameter, branchesCondition, sqlHandler, mqService, auditHandler, paymentController);
}
```

#### `process()` - utilisation du branchesCondition :
```java
int process() {
    try {
        // process responses
        if (parameter.isMqActivate()) {
            this.responseProcessor.start();
            this.sqlHandler.commit();
        }
        // Clean and load payment tables
        this.sqlHandler.purgeWorkingTables(branchesCondition);     // ← PASSE branchesCondition
        this.sqlHandler.loadWorkingTables(branchesCondition);      // ← PASSE branchesCondition
        this.sqlHandler.commit();
        // Create, control, transform and enrich payments
        List<Payment> payments = this.paymentProcessor.buildNewPayments();
        ...
    }
}
```

#### `start()` :
```java
static void start(String[] args, IPaymentSqlHandler sqlHandler, IMessageMqService mqService) {
    int exitStatus;
    try {
        Main main = new Main(args, sqlHandler, mqService);
        Main.setInstance(main);
        updateStatus(main.process());
    } catch (...) { ... }
    System.exit(currentStatus);
}
```

---

## FLUX COMPLET DE L'INJECTION (CONFIRMÉ)

```
1. getBranches(cdeName) → retourne List<String> branches de la DB
2. buildBranchCondition(branches) → " in ('BR1', 'BR2')" (fragment SQL brut)
3. branchesCondition stocké dans Main.branchesCondition
4. Propagé vers:
   a. ResponseProcessor(branchesCondition, ...)
   b. PaymentProcessor(branchesCondition, ...)
   c. sqlHandler.purgeWorkingTables(branchesCondition)
   d. sqlHandler.loadWorkingTables(branchesCondition)
5. Dans PaymentSqlHandler:
   a. getNewPayments(): String.format(SELECT_NEW_PAYMENTS, branchCondition) → INJECTION
   b. Ligne 603: String.format(???, branchCondition) → updateQuery() → INJECTION
```

---

## CONSTANTES SQL VULNÉRABLES (PaymentSqlQueries.java) - TOUTES avec %s

| # | Constante | Pattern vulnérable | Utilisée dans |
|---|-----------|-------------------|---------------|
| 1 | `SELECT_NEW_PAYMENTS` | `where TRIM(M.IMT_CDE_BRANCH) %s` | `getNewPayments()` |
| 2 | `INSERT_OUT_DIFF` | `and TRIM(O.IMT_CDE_BRANCH) %s` | `loadWorkingTables()` |
| 3 | `INSERT_ROLE_DIFF` | `and TRIM(OUT.IMT_CDE_BRANCH) %s` | `loadWorkingTables()` |
| 4 | `INSERT_OUT_DATA` | `where TRIM(D.IMT_CDE_BRANCH) %s` | `loadWorkingTables()` |
| 5 | `INSERT_ROLE_DATA` | `where TRIM(D.IMT_CDE_BRANCH) %s` | `loadWorkingTables()` |
| 6 | `PURGE_OUT_DIFF` | `IN (%s)` | `purgeWorkingTables()` |
| 7 | `PURGE_ROLE_DIFF` | `IN (%s)` | `purgeWorkingTables()` |
| 8 | `SELECT_DE_MSG_KEY_FROM_HISTO` | `TRIM(H.IMT_CDE_BRANCH) %s` | ? |

### SqlHandler.java - VU EN ENTIER
- `prepareStatement()` : OK, utilise bien des `?` et `setString/setInt`
- `updateQuery()` : appelle `prepareStatement()` en interne → même problème si query contient du SQL formaté
- `commit()`, `rollback()`, `closeDbConnection()`, `setAutoCommit()` : OK, pas de SQL

## STATUT : ANALYSE COMPLÈTE ✅

---

## PROBLÈME RACINE IDENTIFIÉ ET CONFIRMÉ

### Le flux vulnérable :
1. `getBranches()` récupère les noms de branches depuis la DB
2. `buildBranchCondition()` construit un **fragment SQL brut** : `" in ('BR1', 'BR2')"`
3. Ce fragment est **injecté directement** dans les requêtes via `String.format(query, branchCondition)`
4. Même si les données viennent de la DB, Fortify le considère comme potentiellement dangereux (second-order injection)

### La requête finale ressemble à :
```sql
SELECT ... FROM TBP_IPMT_IMT_OUT_DIFF M
JOIN ... WHERE TRIM(M.IMT_CDE_BRANCH) in ('BR1', 'BR2')
```

### SOLUTION RECOMMANDÉE
**Approche : Génération dynamique de placeholders `?`**

#### 1. Modifier `buildBranchCondition()` → ne plus construire de SQL, retourner la liste directement
#### 2. Créer une méthode helper pour générer les placeholders :
```java
// Génère " in (?, ?, ?)" avec N placeholders
private static String buildInClausePlaceholders(int count) {
    return " in (" + String.join(", ", Collections.nCopies(count, "?")) + ")";
}
```
#### 3. Modifier les requêtes pour utiliser les placeholders
#### 4. Passer les branches comme Object[] parameters

### Impact des changements :
- `Main.java` : changer branchesCondition de String vers List<String>
- `PaymentSqlHandler.java` : toutes les méthodes utilisant branchCondition
- `PaymentSqlQueries.java` : constantes SQL avec %s
- `ResponseProcessor` et `PaymentProcessor` : si ils utilisent branchCondition
- `IPaymentSqlHandler` : signature des méthodes
- `SqlHandler.java` : potentiellement updateQuery()
