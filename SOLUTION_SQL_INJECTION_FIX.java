// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║        SOLUTION COMPLÈTE - SQL INJECTION FIX (FORTIFY)                     ║
// ║        *** SANS NOUVELLE CLASSE ***                                        ║
// ║                                                                            ║
// ║  On ajoute 2 méthodes protected dans SqlHandler.java (parent existant)     ║
// ║  et on modifie les appelants pour utiliser des paramètres bindés.          ║
// ╚══════════════════════════════════════════════════════════════════════════════╝


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 1 : SqlHandler.java — Ajouter 2 méthodes protected                 ║
// ║  (après prepareStatement(), vers ligne ~128)                               ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// AJOUTER cet import en haut :
import java.util.Collections;
import java.util.List;

// AJOUTER ces 2 méthodes après prepareStatement() (ligne ~128) :

    /**
     * Génère une clause IN paramétrée : "IN (?, ?, ?)"
     * pour remplacer les String.format(%s) vulnérables.
     */
    protected String buildInClause(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("La clause IN doit avoir au moins un paramètre");
        }
        return "IN (" + String.join(", ", Collections.nCopies(count, "?")) + ")";
    }

    /**
     * Convertit une List<String> en Object[] pour les paramètres PreparedStatement.
     */
    protected Object[] toParams(List<String> values) {
        return values.stream().map(String::trim).toArray(Object[]::new);
    }


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 2 : Main.java — Remplacer String par List<String>                   ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ──── 2a. Champ (ligne 43) ────

    // AVANT :
    private String branchesCondition = "";

    // APRÈS :
    private List<String> branches = new ArrayList<>();


// ──── 2b. SUPPRIMER buildBranchCondition() (vers ligne ~153) ────

    // SUPPRIMER EN ENTIER :
    private static String buildBranchCondition(List<String> branches) {
        return " in ('" + branches.stream()
            .map(String::trim)
            .collect(joining("', '")) + "')";
    }


// ──── 2c. Modifier initProcessors() ────

    // AVANT :
    private void initProcessors(String cdeName) throws VarEnvException, SQLException, ArgumentException {
        Main.setInstance(this);
        this.sqlHandler.setAutoCommit(false);
        this.auditHandler = new AuditHandler(cdeName, this.sqlHandler);
        this.auditHandler.loadLogId();
        List<String> branches = this.sqlHandler.getBranches(cdeName);
        this.branchesCondition = buildBranchCondition(branches);
        this.responseProcessor = new ResponseProcessor(branchesCondition, sqlHandler, mqService, auditHandler);
        IPaymentController paymentController = new PaymentController(sqlHandler, auditHandler, cdeName);
        this.paymentProcessor = new PaymentProcessor(parameter, branchesCondition, sqlHandler, mqService, auditHandler, paymentController);
    }

    // APRÈS :
    private void initProcessors(String cdeName) throws VarEnvException, SQLException, ArgumentException {
        Main.setInstance(this);
        this.sqlHandler.setAutoCommit(false);
        this.auditHandler = new AuditHandler(cdeName, this.sqlHandler);
        this.auditHandler.loadLogId();
        this.branches = this.sqlHandler.getBranches(cdeName);  // ← stocker directement la liste
        this.responseProcessor = new ResponseProcessor(branches, sqlHandler, mqService, auditHandler);
        IPaymentController paymentController = new PaymentController(sqlHandler, auditHandler, cdeName);
        this.paymentProcessor = new PaymentProcessor(parameter, branches, sqlHandler, mqService, auditHandler, paymentController);
    }


// ──── 2d. Modifier process() ────

    // AVANT :
    this.sqlHandler.purgeWorkingTables(branchesCondition);
    this.sqlHandler.loadWorkingTables(branchesCondition);

    // APRÈS :
    this.sqlHandler.purgeWorkingTables(branches);
    this.sqlHandler.loadWorkingTables(branches);


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 3 : PaymentSqlQueries.java — Retirer TOUS les %s                   ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// Pour CHAQUE constante ci-dessous, retirer le %s à la fin.
// La clause IN (?, ?, ?) sera ajoutée dynamiquement dans PaymentSqlHandler.

// ──── 3a. SELECT_NEW_PAYMENTS ────

    // AVANT :
    "where TRIM(M.IMT_CDE_BRANCH) %s";

    // APRÈS :
    "where TRIM(M.IMT_CDE_BRANCH) ";


// ──── 3b. INSERT_OUT_DIFF ────

    // AVANT :
    "and TRIM(O.IMT_CDE_BRANCH) %s" +
    "and not exists (select T.IMT_RID_IMT_OUT from ...)";
    // Note: le %s est au milieu, il faut remplacer JUSTE le %s
    // Vérifier la position exacte dans votre code

    // APRÈS :
    // Retirer le %s, la clause IN sera insérée dynamiquement


// ──── 3c. INSERT_ROLE_DIFF ────

    // AVANT :
    "and TRIM(OUT.IMT_CDE_BRANCH) %s" +

    // APRÈS :
    "and TRIM(OUT.IMT_CDE_BRANCH) " +


// ──── 3d. INSERT_OUT_DATA ────

    // AVANT :
    "where TRIM(D.IMT_CDE_BRANCH) %s";

    // APRÈS :
    "where TRIM(D.IMT_CDE_BRANCH) ";


// ──── 3e. INSERT_ROLE_DATA ────

    // AVANT :
    "where TRIM(D.IMT_CDE_BRANCH) %s";

    // APRÈS :
    "where TRIM(D.IMT_CDE_BRANCH) ";


// ──── 3f. PURGE_OUT_DIFF ────

    // AVANT :
    "delete from "+LIQBATCHSCHEMA+".TBP_IPMT_IMT_OUT_DIFF where DF.IMT_RID_IMT_OUT IN (%s)";

    // APRÈS :
    "delete from "+LIQBATCHSCHEMA+".TBP_IPMT_IMT_OUT_DIFF where DF.IMT_RID_IMT_OUT IN (";
    // On fermera la parenthèse dans PaymentSqlHandler après les placeholders


// ──── 3g. PURGE_ROLE_DIFF ────
    // Même pattern que PURGE_OUT_DIFF : retirer le %s du IN()


// ──── 3h. SELECT_DE_MSG_KEY_FROM_HISTO ────

    // AVANT :
    "WHERE TRIM(H.IMT_CDE_BRANCH) %s ";

    // APRÈS :
    "WHERE TRIM(H.IMT_CDE_BRANCH) ";


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 4 : PaymentSqlHandler.java — Corriger les méthodes                  ║
// ║  Remplacer String.format() par buildInClause() + toParams()               ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ==================== 4a. getNewPayments() (ligne ~168) ====================

    // AVANT :
    @Override
    public List<Payment> getNewPayments(String branchCondition) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        try (PreparedStatement ps = this.prepareStatement(
                String.format(SELECT_NEW_PAYMENTS, branchCondition), null);
             ResultSet rs = ps.executeQuery()) {
            // ... mapping des colonnes ...
        }
    }

    // APRÈS :
    @Override
    public List<Payment> getNewPayments(List<String> branches) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        String query = SELECT_NEW_PAYMENTS + buildInClause(branches.size());
        try (PreparedStatement ps = this.prepareStatement(query, toParams(branches));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Payment payment = new Payment();
                payment.setMsgType(SwiftType.getSwiftType(rs.getString("IMT_CDE_MSG_TYPE")));
                payment.setMsgBranch(rs.getString("IMT_CDE_BRANCH"));
                payment.setMsgId(rs.getString("IMT_RID_IMT_OUT"));
                payment.setMsgAmount(rs.getString("IMT_AMT_OUT_TOT_FMT"));
                payment.setMsgCurrency(rs.getString("IMT_CDE_CURRENCY"));
                payment.setValueDate(rs.getString("IMT_DTE_VALUE_DATE_FMT"));
                payment.setEffectiveDate(rs.getString("IMT_DTE_BUSINESS_FMT"));
                payment.setSenderToReceiverInfo(rs.getString("IMT_TXT_SDR_RVR_TX"));
                payment.setPidDeal(rs.getString("PID_DEAL"));
                payment.setPidFacility(rs.getString("IMT_PID_FACILITY"));
                payment.setRidCashflow(rs.getString("IMT_RID_CASHFLOW"));
                payment.setRidOwner(rs.getString("IMT_RID_OWNER"));
                payment.setOwnerType(rs.getString("IMT_CDE_OWNER_TYPE"));
                payment.setCdeBankOp(rs.getString("IMT_CDE_BNK_OP"));
                payment.setDtlCharges(rs.getString("IMT_CDE_DTLS_CHGES"));
                payment.setDealName(rs.getString("DEAL_NME"));
                payment.setFacilityName(rs.getString("FAC_NME"));
                payments.add(payment);
            }
        } catch (SQLException e) {
            LOG.error("Could not get new payments with query " + SELECT_NEW_PAYMENTS, e);
            throw e;
        }
        return payments;
    }


// ==================== 4b. loadWorkingTables() (ligne ~603 - 2ème point Fortify) ====================

    // AVANT :
    @Override
    public void loadWorkingTables(String branchCondition) throws SQLException {
        updateQuery(String.format(INSERT_OUT_DIFF, branchCondition), null);
        updateQuery(String.format(INSERT_ROLE_DIFF, branchCondition), null);
        updateQuery(String.format(INSERT_OUT_DATA, branchCondition), null);
        updateQuery(String.format(INSERT_ROLE_DATA, branchCondition), null);
    }

    // APRÈS :
    @Override
    public void loadWorkingTables(List<String> branches) throws SQLException {
        String inClause = buildInClause(branches.size());
        Object[] params = toParams(branches);

        updateQuery(INSERT_OUT_DIFF + inClause, params);
        updateQuery(INSERT_ROLE_DIFF + inClause, params);
        updateQuery(INSERT_OUT_DATA + inClause, params);
        updateQuery(INSERT_ROLE_DATA + inClause, params);
    }


// ==================== 4c. purgeWorkingTables() ====================

    // AVANT :
    @Override
    public void purgeWorkingTables(String branchCondition) throws SQLException {
        updateQuery(String.format(PURGE_OUT_DIFF, branchCondition), null);
        updateQuery(String.format(PURGE_ROLE_DIFF, branchCondition), null);
    }

    // APRÈS :
    @Override
    public void purgeWorkingTables(List<String> branches) throws SQLException {
        // Pour PURGE : la constante se termine par "IN (" donc on ajoute les ? et ")"
        String placeholders = String.join(", ", Collections.nCopies(branches.size(), "?")) + ")";
        Object[] params = toParams(branches);

        updateQuery(PURGE_OUT_DIFF + placeholders, params);
        updateQuery(PURGE_ROLE_DIFF + placeholders, params);
    }

// Ne pas oublier l'import en haut de PaymentSqlHandler.java :
// import java.util.Collections;  (si pas déjà présent)


// ==================== 4d. Toute autre méthode avec String.format + branchCondition ====================
// Appliquer le MÊME pattern pour SELECT_DE_MSG_KEY_FROM_HISTO et tout
// autre endroit qui utilise String.format avec %s et branchCondition.
// Pattern universel :
//   String query = CONSTANTE_SQL + buildInClause(branches.size());
//   Object[] params = toParams(branches);
//   this.prepareStatement(query, params);  // ou updateQuery(query, params);


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 5 : IPaymentSqlHandler.java — Mettre à jour l'interface             ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

    // AVANT :
    List<Payment> getNewPayments(String branchCondition) throws SQLException;
    void loadWorkingTables(String branchCondition) throws SQLException;
    void purgeWorkingTables(String branchCondition) throws SQLException;

    // APRÈS :
    List<Payment> getNewPayments(List<String> branches) throws SQLException;
    void loadWorkingTables(List<String> branches) throws SQLException;
    void purgeWorkingTables(List<String> branches) throws SQLException;


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 6 : ResponseProcessor + PaymentProcessor                            ║
// ║  Modifier les constructeurs : String → List<String>                        ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ==================== PaymentProcessor.java ====================
// Champ ligne 41 : String branchesCondition → List<String> branches
// 2 constructeurs + buildNewPayments() à modifier

    // ── Champ ──
    // AVANT :
    private final String branchesCondition;  // 3 usages
    // APRÈS :
    private final List<String> branches;     // (ajouter import java.util.List)


    // ── Constructeur 1 (ligne ~47) ──
    // AVANT :
    public PaymentProcessor(InterfaceParameter param, String branchesCondition,
                            IPaymentSqlHandler sqlHandler, IMessageMqService mqService,
                            IAuditHandler auditHandler, IPaymentController paymentController)
            throws VarEnvException {
        this.param = param;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branchesCondition = branchesCondition;
        // ...
    }

    // APRÈS :
    public PaymentProcessor(InterfaceParameter param, List<String> branches,
                            IPaymentSqlHandler sqlHandler, IMessageMqService mqService,
                            IAuditHandler auditHandler, IPaymentController paymentController)
            throws VarEnvException {
        this.param = param;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branches = branches;
        // ...
    }


    // ── Constructeur 2 (ligne ~60) ──
    // AVANT :
    public PaymentProcessor(String branchesCondition, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService, IAuditHandler auditHandler,
                            IPaymentController paymentController)
            throws VarEnvException {
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branchesCondition = branchesCondition;
        // ...
    }

    // APRÈS :
    public PaymentProcessor(List<String> branches, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService, IAuditHandler auditHandler,
                            IPaymentController paymentController)
            throws VarEnvException {
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branches = branches;
        // ...
    }


    // ── buildNewPayments() (ligne ~73) — CRITIQUE ──
    // AVANT :
    List<Payment> newPayments = this.sqlHandler.getNewPayments(this.branchesCondition);
    // APRÈS :
    List<Payment> newPayments = this.sqlHandler.getNewPayments(this.branches);


// ==================== ResponseProcessor.java ====================
// Le branchCondition est stocké mais N'EST PAS utilisé dans des requêtes SQL
// directement. Il faut quand même changer le type pour la cohérence
// et couper la propagation du "tainted data" vue par Fortify.

    // ── Champ (ligne 12) ──
    // AVANT :
    private final String branchCondition;  // 3 usages
    // APRÈS :
    private final List<String> branches;


    // ── Constructeur (ligne ~37) ──
    // AVANT :
    public ResponseProcessor(String branchCondition, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService,
                            IAuditHandler logHandler) {
        this.branchCondition = branchCondition;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.auditHandler = logHandler;
    }

    // APRÈS :
    public ResponseProcessor(List<String> branches, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService,
                            IAuditHandler logHandler) {
        this.branches = branches;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.auditHandler = logHandler;
    }

    // Si branchCondition est utilisé quelque part dans ResponseProcessor
    // dans du SQL, appliquer le même pattern buildInClause() + toParams().
    // ⚠️ OUI, branchCondition EST utilisé dans 2 appels SQL :

    // ── processAckNackFromItl() ligne ~95 ──
    // AVANT :
    nbNack = this.sqlHandler.updatePendingSwiftMsgtoFailInDB(this.branchCondition);
    // APRÈS :
    nbNack = this.sqlHandler.updatePendingSwiftMsgtoFailInDB(this.branches);

    // ── processItlAck() ligne ~117 ──
    // AVANT :
    String msgKey = this.sqlHandler.getMsgKeyForItl(this.branchCondition, day, seq);
    // APRÈS :
    String msgKey = this.sqlHandler.getMsgKeyForItl(this.branches, day, seq);


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 7 : PaymentSqlHandler.java — 2 méthodes supplémentaires            ║
// ║  updatePendingSwiftMsgtoFailInDB() et getMsgKeyForItl()                    ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// Il faut me montrer ces 2 méthodes dans PaymentSqlHandler.java pour que je
// puisse écrire la correction exacte. Mais le pattern sera le même :

// ==================== updatePendingSwiftMsgtoFailInDB() ====================

    // AVANT (probable) :
    @Override
    public int updatePendingSwiftMsgtoFailInDB(String branchCondition) throws PaymentException {
        // utilise probablement String.format(SOME_QUERY, branchCondition) ou
        // concatène branchCondition dans la requête
    }

    // APRÈS :
    @Override
    public int updatePendingSwiftMsgtoFailInDB(List<String> branches) throws PaymentException {
        String query = UPDATE_PENDING_QUERY + buildInClause(branches.size());
        // ou le nom exact de la constante SQL
        Object[] params = toParams(branches);
        updateQuery(query, params);  // ou prepareStatement selon l'implem
    }


// ==================== getMsgKeyForItl() ====================

    // AVANT (probable) :
    @Override
    public String getMsgKeyForItl(String branchCondition, String day, String seq) throws PaymentException {
        // utilise probablement String.format(SOME_QUERY, branchCondition)
        // avec day et seq comme paramètres supplémentaires
    }

    // APRÈS :
    @Override
    public String getMsgKeyForItl(List<String> branches, String day, String seq) throws PaymentException {
        String query = GET_MSG_KEY_QUERY + buildInClause(branches.size());
        // Fusionner les paramètres branches + day + seq :
        Object[] branchParams = toParams(branches);
        Object[] allParams = new Object[branchParams.length + 2];
        System.arraycopy(branchParams, 0, allParams, 0, branchParams.length);
        allParams[branchParams.length] = day;
        allParams[branchParams.length + 1] = seq;
        // Attention: l'ordre des ? dans la requête doit correspondre !
        // Si branchCondition est à la fin de la query, mettre day/seq d'abord
        try (PreparedStatement ps = this.prepareStatement(query, allParams);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString("MSG_KEY");
            }
        }
        return null;
    }


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 8 : IPaymentSqlHandler.java — Signatures supplémentaires           ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

    // AJOUTER/MODIFIER dans l'interface :
    // AVANT :
    int updatePendingSwiftMsgtoFailInDB(String branchCondition) throws PaymentException;
    String getMsgKeyForItl(String branchCondition, String day, String seq) throws PaymentException;

    // APRÈS :
    int updatePendingSwiftMsgtoFailInDB(List<String> branches) throws PaymentException;
    String getMsgKeyForItl(List<String> branches, String day, String seq) throws PaymentException;


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  RÉSUMÉ DES MODIFICATIONS                                                  ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
/*
  ╔═════════════════════════════════════╦═══════════════════════════════════════════╗
  ║ FICHIER                            ║ MODIFICATION                             ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ SqlHandler.java                    ║ + buildInClause() et toParams()          ║
  ║                                    ║ + imports Collections, List              ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ Main.java                          ║ String branchesCondition → List<String>  ║
  ║                                    ║ SUPPRIMER buildBranchCondition()         ║
  ║                                    ║ Modifier initProcessors() et process()   ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ PaymentSqlQueries.java             ║ Retirer %s des constantes SQL            ║
  ║                                    ║ (8+ constantes identifiées)              ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ PaymentSqlHandler.java             ║ getNewPayments(List<String>)             ║
  ║                                    ║ loadWorkingTables(List<String>)          ║
  ║                                    ║ purgeWorkingTables(List<String>)         ║
  ║                                    ║ updatePendingSwiftMsgtoFailInDB(List)    ║
  ║                                    ║ getMsgKeyForItl(List, day, seq)          ║
  ║                                    ║ buildInClause() + toParams() partout     ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ IPaymentSqlHandler.java            ║ 5 signatures: String → List<String>     ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ PaymentProcessor.java              ║ Champ: String → List<String>            ║
  ║                                    ║ 2 constructeurs: String → List<String>  ║
  ║                                    ║ buildNewPayments(): passer branches     ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ IPaymentProcessor.java             ║ (si interface existe) adapter            ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ ResponseProcessor.java             ║ Champ: String → List<String>            ║
  ║                                    ║ Constructeur: String → List<String>     ║
  ║                                    ║ processAckNackFromItl(): passer branches ║
  ║                                    ║ processItlAck(): passer branches        ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ IResponseProcessor.java            ║ (si interface existe) adapter            ║
  ╚═════════════════════════════════════╩═══════════════════════════════════════════╝

  AUCUNE NOUVELLE CLASSE CRÉÉE. Tout est dans l'existant.
*/


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  POURQUOI CETTE SOLUTION RÉSOUT FORTIFY                                    ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
/*
  AVANT (VULNÉRABLE):
  ┌────────────────────────────────────────────────────────────────────────┐
  │  getBranches() → ["BR1","BR2"]                                        │
  │  buildBranchCondition() → " in ('BR1', 'BR2')"      ← SQL BRUT      │
  │  String.format(query, branchCondition)               ← CONCATÉNATION │
  │  prepareStatement("...WHERE branch in ('BR1','BR2')")← VULNÉRABLE    │
  └────────────────────────────────────────────────────────────────────────┘

  APRÈS (SÉCURISÉ):
  ┌────────────────────────────────────────────────────────────────────────┐
  │  getBranches() → ["BR1","BR2"]                                        │
  │  query + buildInClause(2) → "...WHERE branch IN (?, ?)" ← SAFE       │
  │  toParams(branches) → Object[]{"BR1","BR2"}                           │
  │  prepareStatement(query, params)                                      │
  │  → ps.setString(1, "BR1");  ps.setString(2, "BR2")  ← PARAMÉTRÉ     │
  └────────────────────────────────────────────────────────────────────────┘

  ✅ Aucune donnée n'est jamais injectée dans le SQL
  ✅ Tout passe par PreparedStatement avec setString()
  ✅ Fortify ne détectera plus de SQL injection
  ✅ Pas de nouvelle classe
  ✅ Impact minimal sur le code existant
*/
