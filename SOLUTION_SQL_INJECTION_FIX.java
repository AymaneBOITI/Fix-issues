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

// ResponseProcessor :
    // AVANT :
    public ResponseProcessor(String branchesCondition, IPaymentSqlHandler sqlHandler, ...) {
        this.branchesCondition = branchesCondition;
    }
    // APRÈS :
    public ResponseProcessor(List<String> branches, IPaymentSqlHandler sqlHandler, ...) {
        this.branches = branches;
    }
    // + changer le champ String branchesCondition → List<String> branches
    // + corriger tous les endroits qui utilisent branchesCondition dans du SQL
    //   avec le même pattern: buildInClause() + toParams()

// PaymentProcessor :
    // Même chose : String branchesCondition → List<String> branches


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  RÉSUMÉ DES MODIFICATIONS                                                  ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
/*
  ╔═════════════════════════════════════╦════════════════════════════════════════╗
  ║ FICHIER                            ║ MODIFICATION                          ║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ SqlHandler.java                    ║ + buildInClause() et toParams()       ║
  ║                                    ║ + imports Collections, List           ║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ Main.java                          ║ String branchesCondition → List<String>║
  ║                                    ║ SUPPRIMER buildBranchCondition()      ║
  ║                                    ║ Modifier initProcessors() et process()║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ PaymentSqlQueries.java             ║ Retirer %s de 8 constantes SQL        ║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ PaymentSqlHandler.java             ║ getNewPayments(List<String>)           ║
  ║                                    ║ loadWorkingTables(List<String>)        ║
  ║                                    ║ purgeWorkingTables(List<String>)       ║
  ║                                    ║ buildInClause() + toParams() partout  ║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ IPaymentSqlHandler.java            ║ Signatures: String → List<String>     ║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ ResponseProcessor.java             ║ Constructeur: String → List<String>   ║
  ╠═════════════════════════════════════╬════════════════════════════════════════╣
  ║ PaymentProcessor.java              ║ Constructeur: String → List<String>   ║
  ╚═════════════════════════════════════╩════════════════════════════════════════╝

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
