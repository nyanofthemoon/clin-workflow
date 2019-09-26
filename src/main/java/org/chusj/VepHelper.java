package org.chusj;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;

import static org.chusj.RedisGeneSetHelper.getMembersForEnsId;
import static org.chusj.VEPSparkDriverProgram.getMD5Hash;


public class VepHelper {

    private static float avgFuncAnnoPerMutation = 0.0f;
    private static int countFuncAnnoPerMutation = 0;
    private static int countMutation =0;
    private static int countRedisCalls =0;
    private static NumberFormat NF = NumberFormat.getInstance();
    private static JSONObject lastOne;
    private static boolean toPrint = false;
    private static final int CLINVAR = 0;
    private static final int OMIM = 1;
    private static final int ENSEMBL = 2;
    private static final int ORPHANET = 3;
    private static final int DBSNP = 4;
    private static final int PUBMED = 5;
    private static final int CLINVAR_SIG = 6;
    private static final int CLINVAR_TRAIT = 7;
    private static final int TYPES = 8;
    private static final int GENES = 9;
    private static final int PHENO = 10;




    public static void main(String[] args) throws Exception {

        if (args.length < 2) {
            String[] newArgs = {"out10000-3.txt","3"};
            args = newArgs;
        }

        String extractFile = args[0];
        String nbPatient = args[1];

        try (BufferedReader buf = new BufferedReader(new FileReader(extractFile))) {

            String fetchedLine;


            // Record and prep metaData
            buf.readLine();

            // Main - Child=14140,Mother=14141,Father=14142

            //String[] pedigree = {"14140,P","14141,M", "14142,F"};

            Properties pedigreeProps = getPropertiesFromFile("pedigree.properties");

            //pedigreeProps.forEach((prop) -> System.out.println("prop="+prop));
            //for (int i=0; i<pedigreeProps.toString())
            System.out.println(pedigreeProps.toString());

            while (true) {
                fetchedLine = buf.readLine();
                if (fetchedLine == null) {
                    break;
                } else {
                    JSONObject propertiesOneMutation = processVcfDataLine(fetchedLine, "dn,dq", pedigreeProps);
                    if (toPrint) {
                        System.out.println(fetchedLine);
                        //System.out.println(propertiesOneMutation.toString(2));
                    }


                    //lastOne = propertiesOneMutation;
                    // extract donor info if not found
//                    String qual = (String) propertiesOneMutation.remove("qual");
//                    String filter = (String) propertiesOneMutation.remove("filter");
                    //JSONArray donorArray = (JSONArray) propertiesOneMutation.remove("donor");
                    //System.out.println("donors:" + donorArray.toString(2));
//                    for (int i=0; i<donorArray.length(); i++) {
//                        JSONObject donor = (JSONObject) donorArray.get(i);
//                        System.out.println("donors:" + donor.toString(2));
//                    }


                }
            }
        }
        if (countMutation>0) {
            avgFuncAnnoPerMutation = (float) countFuncAnnoPerMutation / countMutation;
        }

        System.out.println("lastOne="+lastOne.toString(2));

        System.out.println("\navgFuncAnnoPerMutation="+avgFuncAnnoPerMutation+"  mutationCount="+ countMutation);
        System.out.println("redisCallsCount="+ countRedisCalls);
    }


    static JSONObject processVcfDataLine(String extractedLine, String OptionStr, Properties pedigreeProps) {

        //CHROM	POS	ID	REF	ALT	QUAL	FILTER	DP	MQ	MQRankSum
        // ReadPosRankSum	LOD	FractionInformativeReads	SNP	MNP	INS	DEL	MIXED	HOM	HET
        // GEN[*].GT	GEN[*].AD	GEN[*].AF	GEN[*].F1R2	GEN[*].F2R1	GEN[*].DP	GEN[*].SB	GEN[*].MB	CSQ
        //System.out.print(".");

        String[] lineValueArray = extractedLine.split("\t");
        // dynamic positioning system -- pos counter++
        toPrint = false;
        int pos = 0;
        int impactScore;
        String chrom = lineValueArray[pos++];
        if ("CHROM".equalsIgnoreCase(chrom)) {
            return null; // Meta data line
        }
        // System.out.println("lineValueArray.length="+lineValueArray.length);
        //boolean db_snp ; clinvarIds, omimIds, emsemblIds, orphanetIds , pubmedIds, clinvarSig, Types, PHENO, trait, genes

        boolean[] dbExt = {false, false, false, false, false, false, false, false, false, false, false};
        List<Set<String>> dbExtId = new ArrayList<>();

        for (int i=0; i<dbExt.length; i++) {
            dbExtId.add(i, new HashSet<>());
        }


        Set<Gene> geneSet = new HashSet<>();

        Map<Integer, FunctionalAnnotation> faMap = new HashMap<>();
        Map<String, Transcript> deStructuredTranscript = new HashMap<>();


        JSONObject propertiesOneMutation = new JSONObject();
        JSONArray donorArray = new JSONArray();
        JSONArray phenotypesArray = new JSONArray();
        JSONObject bdExtObj = new JSONObject();
        JSONObject clinvarObj = new JSONObject();
        JSONObject geneObj;
        JSONArray geneArray = new JSONArray();

        String[] pedigree = pedigreeProps.getProperty("pedigree").split(",");
        String familyId = pedigreeProps.getProperty("familyId");
        String[] patientId = pedigreeProps.getProperty("patientId").split(",");
        String[] relation = pedigreeProps.getProperty("relation").split(",");
        String studyId = pedigreeProps.getProperty("studyId");
        String sequencingStrategy = pedigreeProps.getProperty("sequencingStrategy");
        String organizationId = pedigreeProps.getProperty("organizationId");
        String practitionerId = pedigreeProps.getProperty("practitionerId");
        propertiesOneMutation.put("assemblyVersion", pedigreeProps.getProperty("assemblyVersion"));
        propertiesOneMutation.put("annotationTool", pedigreeProps.getProperty("annotationTool"));

        LocalDate localDate = LocalDate.now();
        propertiesOneMutation.put("lastAnnotationUpdate", localDate);

        int nbDonor = pedigree.length;
        JSONObject[] arrayDonor = new JSONObject[nbDonor];
        for (int i=0; i<nbDonor; i++) {
            arrayDonor[i] = new JSONObject();
        }

        countMutation++;
        String position = lineValueArray[pos++];

        String dbSNP_ID = lineValueArray[pos++];
        if (addStrToJsonObject("dbSNP_ID", dbSNP_ID, propertiesOneMutation, false)) {
            // bdExtObj.put( new JSONObject().put("dbSNP",
            dbExt[DBSNP] =  true;
            addStrToListSet(dbExtId, DBSNP, dbSNP_ID);
        }
        String reference = lineValueArray[pos++];

        String alt = lineValueArray[pos++].replace(",<NON_REF>", ""); // CT,<NON_REF> or G,TGG,<NON_REF>
        String qual = lineValueArray[pos++];
        String filter = lineValueArray[pos++];

        String dpS = lineValueArray[pos++];
        String mqS = lineValueArray[pos++];
        String mqRankSum = lineValueArray[pos++];
        String readPosRankSum = lineValueArray[pos++];
        String lod = lineValueArray[pos++];
        String fractionInformativeReads = lineValueArray[pos++];

        boolean snp = Boolean.valueOf(lineValueArray[pos++]);
        boolean mnp = Boolean.valueOf(lineValueArray[pos++]);
        boolean ins = Boolean.valueOf(lineValueArray[pos++]);
        boolean del = Boolean.valueOf(lineValueArray[pos++]);
        boolean mixed = Boolean.valueOf(lineValueArray[pos++]);
        boolean hom = Boolean.valueOf(lineValueArray[pos++]);
        boolean het = Boolean.valueOf(lineValueArray[pos++]);


//        if (snp) propertiesOneMutation.put("type", "SNP");
//        if (mnp) propertiesOneMutation.put("type", "MNP");
//        if (ins) propertiesOneMutation.put("type", "INS");
//        if (del) propertiesOneMutation.put("type", "DEL");
//        if (mixed) propertiesOneMutation.put("type", "MIXED");

        propertiesOneMutation.put("altAllele", alt);

        String[] gt = lineValueArray[pos++].split(",");
        String[] gq = lineValueArray[pos++].split(",");
        String adStr = lineValueArray[pos++];
        //System.out.println(adStr);
        String[] ad = adStr.split(",", -1);
        String[] af = lineValueArray[pos++].split(",");
        String[] f1r2 = lineValueArray[pos++].split(",");
        String[] f2r1 = lineValueArray[pos++].split(",");
        String[] genDP = lineValueArray[pos++].split(",");
        String[] sb = lineValueArray[pos++].split(",");
        String[] mb = lineValueArray[pos++].split(",");


        String dnS = lineValueArray[pos++];
        //String dqS = lineValueArray[pos++];
        String homS = lineValueArray[pos++];
        String hetS = lineValueArray[pos++];
        String hiConfDeNovo = lineValueArray[pos++];
        String lodS = lineValueArray[pos++];

        String csq = lineValueArray[pos];

        String[] csqArray = csq.split(",");  // return functionalAnnotation array
        countFuncAnnoPerMutation += csqArray.length;

        String chrPos = chrom.substring(3); // remove 'chr'
        String dnaChange = reference + ">" + alt.split(",")[0];
        String mutationId = "chr" + chrPos + ":g." + position + dnaChange;
        String uid = getMD5Hash(mutationId);


        propertiesOneMutation.put("id", uid);
        propertiesOneMutation.put("mutationId", mutationId);
        propertiesOneMutation.put("dnaChange", dnaChange);
        propertiesOneMutation.put("chrom", chrPos);
        propertiesOneMutation.put("refAllele", reference);
        propertiesOneMutation.put("start", Long.valueOf(position));

        JSONArray functionalAnnotations = new JSONArray();
        JSONObject frequencies = null;
        JSONObject funcAnnotation;


        impactScore = 0;
        for (String s : csqArray) {

            funcAnnotation = processVepAnnotations(s, dbExtId, dbExt, geneSet, deStructuredTranscript);

            frequencies = (JSONObject) funcAnnotation.remove("frequencies");
            functionalAnnotations.put(funcAnnotation);
            String funcAnnotationImpact = (String) funcAnnotation.remove("impact");
            int funcAnnotationScore = getImpactScore(funcAnnotationImpact);
            if (funcAnnotationScore > impactScore ) impactScore = funcAnnotationScore;

            String gene = "";
            String geneId = "";
            if (!funcAnnotation.isNull("geneAffectedSymbol")) {
                gene = (String) funcAnnotation.remove("geneAffectedSymbol");
                geneId = (String) funcAnnotation.remove("geneAffectedId");
            }
            String consequence = (String) funcAnnotation.remove("consequence");
            Long strand = null;
            if (!funcAnnotation.isNull("strand")) {
                strand = (long) funcAnnotation.remove("strand");
            }
            String aaChange = "";
            if (!funcAnnotation.isNull("aaChange")) {
                aaChange = (String) funcAnnotation.remove("aaChange");
            }
            String cdnaChange = "";
            if (!funcAnnotation.isNull("cdnaChange")) {
                cdnaChange = (String) funcAnnotation.remove("cdnaChange");
            }
            //FunctionalAnnotation(String gene, String aaChange, String consequence, String codingDNAChange, long strand)
            JSONObject scores = (JSONObject) funcAnnotation.remove("conservationsScores");
            JSONObject predictions = null;
            if (!funcAnnotation.isNull("predictions")) {
                predictions = (JSONObject) funcAnnotation.remove("predictions");
            }
            String biotype = (String) funcAnnotation.remove("biotype");

            FunctionalAnnotation functionalAnnotation = new FunctionalAnnotation(gene, aaChange, consequence, cdnaChange, strand);
            functionalAnnotation.setGeneId(geneId);
            functionalAnnotation.setImpact(funcAnnotationImpact);
            functionalAnnotation.setScores(scores);
            if (predictions !=null) functionalAnnotation.setPredictions(predictions);
            functionalAnnotation.setBiotype(biotype);


            Integer hash = functionalAnnotation.hashCode();
            if (faMap.containsKey(hash)) {
                FunctionalAnnotation prevFA = faMap.get(hash);
                prevFA.getTheRest().put(funcAnnotation);
            } else {
                JSONArray ja = new JSONArray();
                ja.put(funcAnnotation);
                functionalAnnotation.setTheRest(ja);
                faMap.put(hash, functionalAnnotation);
            }
        }

        JSONArray consequences = new JSONArray();
        faMap.forEach((k,v) -> consequences.put(v.getJsonObj()));

        propertiesOneMutation.put("consequences", consequences);
        propertiesOneMutation.put("impactScore", impactScore);
        //propertiesOneMutation.put("type",  variant_class.get("type"));
        String types = toStringList(dbExtId.get(TYPES));
        propertiesOneMutation.put("type",  types);
        propertiesOneMutation.put("frequencies", frequencies);

        for (int i=0; i< nbDonor; i++) {

            arrayDonor[i].put("lastUpdate", localDate);
            //arrayDonor[i].put("phenotypes", phenotypesArray);
            addNumberToJsonObject("quality", qual, arrayDonor[i], false, 'f');
            arrayDonor[i].put("filter", filter);
            arrayDonor[i].put("gt", gt[i]);
            arrayDonor[i].put("zygosity", zygosity(gt[i]));
            addNumberToJsonObject("gq", gq[i], arrayDonor[i], false, 'l');
            String adS;
            // bug in snpSift Extract...  does not keep correctly unknown value . in GEN[*].AD
            // but in that regards, the information is so bad that it's okay to put 0,1 or 1,0
            if (ad.length < nbDonor * 2) {
                if ("./.".equalsIgnoreCase(gt[i])) {
                    adS = "0,0";
                } else if ("1/0".equalsIgnoreCase(gt[i])) {
                    adS = "0,1";
                } else if ("0/1".equalsIgnoreCase(gt[i])) {
                    adS = "0,1";
                } else { //1/1
                    adS = "1,0";
                }
            } else {
                adS = ad[i * 2] + "," + ad[(i * 2) + 1];
            }
            arrayDonor[i].put("ad", adS);
//            arrayDonor[i].put("af", );
            addNumberToJsonObject("af", af[i], arrayDonor[i], false, 'f');
            arrayDonor[i].put("f1r2", f1r2[i*2] + "," + f1r2[i*2+1]);
            arrayDonor[i].put("f2r1", f2r1[i*2] + "," + f2r1[i*2+1]);
//            arrayDonor[i].put("dp", );
            addNumberToJsonObject("dp", genDP[i], arrayDonor[i], false, 'l');
            arrayDonor[i].put("sb", sb[i]);
            arrayDonor[i].put("mb", mb[i]);
            addNumberToJsonObject("mq", mqS, arrayDonor[i], false, 'l'); //.E0
            addNumberToJsonObject("mqRankSum", mqRankSum, arrayDonor[i], false, 'f'); //".8116E1"
            addNumberToJsonObject("depth", dpS, arrayDonor[i], false, 'l');
            addNumberToJsonObject("readPosRankSum", readPosRankSum, arrayDonor[i], false, 'f');
            arrayDonor[i].put("specimenId", pedigree[i]);
            arrayDonor[i].put("patientId", patientId[i]);
            arrayDonor[i].put("familyId", familyId);
            arrayDonor[i].put("relation", relation[i]);
            arrayDonor[i].put("studyId", studyId);
            arrayDonor[i].put("practitionerId", practitionerId);
            arrayDonor[i].put("organizationId", organizationId);
            arrayDonor[i].put("sequencingStrategy", sequencingStrategy);

            donorArray.put(arrayDonor[i]);

        }

        addSetsToArrayToObj(dbExtId, DBSNP, bdExtObj, "dbSNP", "id");
        String clinvarids = toStringList(dbExtId.get(CLINVAR));
        clinvarObj.put("clinvar_id", clinvarids);
        addSetsToArrayToObj(dbExtId, CLINVAR, bdExtObj, "clinvar", "id");

        addSetsToArrayToObj(dbExtId, ENSEMBL, bdExtObj, "ensembl", "id");

        addSetsToArrayToObj(dbExtId, OMIM, bdExtObj, "omim", "id");

        addSetsToArrayToObj(dbExtId, ORPHANET, bdExtObj, "orphanet", "id");

        addSetsToArrayToObj(dbExtId, PUBMED, bdExtObj, "pubmed", "id");

        for (Gene gene: geneSet) {
            geneObj = new JSONObject();
            geneObj.put("geneSymbol", gene.getGeneSymbol());
            geneObj.put("ensemblId", gene.getEnsemblId());
            geneObj.put("biotype", gene.getBiotype());
            addGeneSetsToObj(gene.getEnsemblId(), geneObj);
            geneArray.put(geneObj);
        }


        String clsig = toStringList(dbExtId.get(CLINVAR_SIG));
        if (!clsig.isEmpty()) { clinvarObj.put( "clinvar_clinsig", clsig ); }
        String cltraits = toStringList(dbExtId.get(CLINVAR_TRAIT));

        addSetsToArrayToObj(dbExtId, CLINVAR_TRAIT, clinvarObj, "clinvar_trait", "id");


        propertiesOneMutation.put("bdExt", bdExtObj);
        propertiesOneMutation.put("clinvar", clinvarObj);
        propertiesOneMutation.put("donors", donorArray);
        propertiesOneMutation.put("genes", geneArray);

        //uid = getSHA1Hash(familyId+ "@" + mutationId);

        //propertiesOneMutation.put("_id", uid);
        if ( dbExt[CLINVAR]  || dbExt[OMIM] || dbExt[ORPHANET]  ) //dbExt[DBSNP]
            lastOne = propertiesOneMutation;



        return propertiesOneMutation;

    }

    private static JSONObject processVepAnnotations(String csqLine, List<Set<String>> dbExtId, boolean[] dbExt, Set<Gene> geneSet, Map<String, Transcript> deStructuredTranscript ) {

        //System.out.print("\n"+csqLine);
        String[] functionalAnnotationArray = csqLine.split("[|]", -1);
        // dynamic positioning system -- pos counter++

//        for (String s : functionalAnnotationArray) {
//            String val = s;
//
//            //System.out.print(i+"'"+val+"'");
//        }

        //System.out.print(id+"("+functionalAnnotationArray.length+")");
        //CHROM	POS	ID	REF	ALT	QUAL	FILTER	DP	MQ	MQRankSum	ReadPosRankSum	LOD	FractionInformativeReads	SNP	MNP	INS	DEL	MIXED	HOM	HET	GEN[*].GT	GEN[*].GQ	GEN[*].AD	GEN[*].AF	GEN[*].F1R2	GEN[*].F2R1	GEN[*].DP	GEN[*].SB	GEN[*].MB	GEN[*].DN	GEN[*].DQ	hiConfDeNovo	CSQ
        //CHROM	POS	ID	REF	ALT	QUAL	FILTER	DP	MQ	MQRankSum	ReadPosRankSum	LOD	FractionInformativeReads	SNP	MNP	INS	DEL	MIXED	HOM	HET	GEN[*].GT	            GEN[*].AD	GEN[*].AF	GEN[*].F1R2	GEN[*].F2R1	GEN[*].DP	GEN[*].SB	GEN[*].MB	CSQ

        int pos = 0;

        //System.out.println(functionalAnnotationArray.length);
        if (functionalAnnotationArray.length < 408) {
            System.out.println(" " + csqLine + " short");
        }

        JSONObject funcAnnotation = new JSONObject();
        JSONObject funcAnnoProperties = new JSONObject();
        JSONObject frequencies = new JSONObject();
        JSONObject frequencyExAc = new JSONObject();
        JSONObject frequency1000Gp3 = new JSONObject();
        JSONObject frequencyEsp6500 = new JSONObject();
        JSONObject frequencyGnomadEx = new JSONObject();
        JSONObject frequencyGnomadGen = new JSONObject();
        JSONObject prediction = new JSONObject();
        JSONObject conservation = new JSONObject();


        String cdnaChange;

        //0 - Allele|Consequence|IMPACT|SYMBOL|Gene|Feature_type|Feature|BIOTYPE|EXON|INTRON
        String Allele = functionalAnnotationArray[pos++];
        addStrToJsonObject("allele", Allele, funcAnnotation, false);
//        String Consequence = functionalAnnotationArray[pos++];
        addStrToJsonObject("consequence", functionalAnnotationArray[pos++], funcAnnotation, false);
        String impact = functionalAnnotationArray[pos++];
        addStrToJsonObject("impact", impact, funcAnnotation, false);
        String symbol = functionalAnnotationArray[pos++];
//        addStrToJsonObject("gene", , funcAnnotation, false);
        String Gene = functionalAnnotationArray[pos++];
        addStrToJsonObject("ensemblTranscriptId", Gene, funcAnnotation, false);
        addStrToJsonObject("featureType", functionalAnnotationArray[pos++], funcAnnotation, false);
        String featureId = functionalAnnotationArray[pos++];
        addStrToJsonObject("featureId", featureId , funcAnnotation, false);
        String biotype = functionalAnnotationArray[pos++];
        addStrToJsonObject("biotype", biotype, funcAnnotation, false);
        String EXON = functionalAnnotationArray[pos++];
//        addStrToJsonObject("exon", functionalAnnotationArray[pos++], funcAnnotation, false);
        String INTRON = functionalAnnotationArray[pos++];
//        addStrToJsonObject("intron", functionalAnnotationArray[pos++], funcAnnotation, false);
        // 9 - HGVSc|HGVSp|cDNA_position|CDS_position|Protein_position|Amino_acids|Codons|Existing_variation|DISTANCE|STRAND
        String HGVSc = functionalAnnotationArray[pos++];
        String HGVSp = functionalAnnotationArray[pos++];
//        addStrToJsonObject("hgvsC", functionalAnnotationArray[pos++], funcAnnotation, false);
//        addStrToJsonObject("hgvsP", functionalAnnotationArray[pos++], funcAnnotation, false);
        String cdnaPos = functionalAnnotationArray[pos++];
//        addStrToJsonObject("cdnaPos", cdnaPos , funcAnnotation, false);
        String CDS_position = functionalAnnotationArray[pos++];
//        addStrToJsonObject("cdsPos", functionalAnnotationArray[pos++] , funcAnnotation, false);
        String Protein_position = functionalAnnotationArray[pos++];
//        addStrToJsonObject("ProteinPos", functionalAnnotationArray[pos++] , funcAnnotation, false);
        String Amino_acids = functionalAnnotationArray[pos++];
//        addStrToJsonObject("aminoAcids", Amino_acids , funcAnnotation, false);
        String Codons = functionalAnnotationArray[pos++];
//        addStrToJsonObject("codons", functionalAnnotationArray[pos++], funcAnnotation, false);
        String Existing_variation = functionalAnnotationArray[pos++];
        String DISTANCE = functionalAnnotationArray[pos++];
//        addNumberToJsonObject("distance", functionalAnnotationArray[pos++] , funcAnnotation, false, 'l');
        addNumberToJsonObject("strand", functionalAnnotationArray[pos++] , funcAnnotation, false, 'l'); // can be empty
        //19 - |FLAGS|VARIANT_CLASS|SYMBOL_SOURCE
        String FLAGS = functionalAnnotationArray[pos++];
        //20
        String VARIANT_CLASS = functionalAnnotationArray[pos++];
        //System.out.print("-vclass="+VARIANT_CLASS);
        //JSONObject variant_class = new JSONObject();
        //addStrToJsonObject("type", VARIANT_CLASS, variant_class, false);
        if (!VARIANT_CLASS.isEmpty()) {
            dbExt[TYPES] = true;
            addStrToListSet(dbExtId, TYPES, VARIANT_CLASS);
        }
        //21
        String SYMBOL_SOURCE = functionalAnnotationArray[pos++];
        // HGNC_ID|CANONICAL|GIVEN_REF|USED_REF|BAM_EDIT|
        String HGNC_ID = functionalAnnotationArray[pos++];
        addBooleanToJsonObject("canonical", functionalAnnotationArray[pos++], funcAnnotation, false);

        String GIVEN_REF = functionalAnnotationArray[pos++];
        String USED_REF = functionalAnnotationArray[pos++];
        String BAM_EDIT = functionalAnnotationArray[pos++];

        // +4 CLIN_SIG|SOMATIC|PHENO|PUBMED

        String CLIN_SIGStr = functionalAnnotationArray[pos++];

        String SOMATIC = functionalAnnotationArray[pos++];

        String PHENOStr = functionalAnnotationArray[pos++];
        if (!PHENOStr.isEmpty()) {
            dbExt[PHENO] = true;
            addStrToListSet(dbExtId, PHENO, PHENOStr);
        }
        String pubmed = functionalAnnotationArray[pos++];

        if (!pubmed.isEmpty()) {
            dbExt[PUBMED] = true;
            addStrToListSet(dbExtId, PUBMED, pubmed);
        }

        addNumberToJsonObject("AC", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'l');
        addNumberToJsonObject("AF", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'f');
        addNumberToJsonObject("AFR_AC", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'l');
        // 29+5
        addNumberToJsonObject("AFR_AF", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'f');
        addNumberToJsonObject("AMR_AC", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'l');
        addNumberToJsonObject("AMR_AF", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'f');
        addNumberToJsonObject("EAS_AC", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'l');
        addNumberToJsonObject("EAS_AF", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'f');
        addNumberToJsonObject("EUR_AC", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'l');
        addNumberToJsonObject("EUR_AF", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'f');
        addNumberToJsonObject("SAS_AC", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'l');
        addNumberToJsonObject("SAS_AF", functionalAnnotationArray[pos++] , frequency1000Gp3, true, 'f');
        String ALSPAC_AC = functionalAnnotationArray[pos++];
        //39 -
        String ALSPAC_AF = functionalAnnotationArray[pos++];
        String APPRIS = functionalAnnotationArray[pos++];
        String Aloft_Confidence = functionalAnnotationArray[pos++];
        String Aloft_Fraction_transcripts_affected = functionalAnnotationArray[pos++];
        String Aloft_pred = functionalAnnotationArray[pos++];
        String Aloft_prob_Dominant = functionalAnnotationArray[pos++];
        String Aloft_prob_Recessive = functionalAnnotationArray[pos++];
        String Aloft_prob_Tolerant = functionalAnnotationArray[pos++];
        String AltaiNeandertal = functionalAnnotationArray[pos++];
        String Ancestral_allele = functionalAnnotationArray[pos++];
        //49 -
        String CADD_phred = functionalAnnotationArray[pos++];
        String CADD_raw = functionalAnnotationArray[pos++];
        String CADD_raw_rankscore = functionalAnnotationArray[pos++];
        String DANN_rankscore = functionalAnnotationArray[pos++];
        String DANN_score = functionalAnnotationArray[pos++];
        String DEOGEN2_pred = functionalAnnotationArray[pos++];
        String DEOGEN2_rankscore = functionalAnnotationArray[pos++];
        String DEOGEN2_score = functionalAnnotationArray[pos++];
        String Denisova = functionalAnnotationArray[pos++];
        addNumberToJsonObject("AA_AC", functionalAnnotationArray[pos++] , frequencyEsp6500, true, 'l');
        //59 -
        addNumberToJsonObject("AA_AF", functionalAnnotationArray[pos++] , frequencyEsp6500, true, 'f');
        addNumberToJsonObject("EA_AC", functionalAnnotationArray[pos++] , frequencyEsp6500, true, 'l');
        addNumberToJsonObject("EA_AF", functionalAnnotationArray[pos++] , frequencyEsp6500, true, 'f');
        String Eigen_PC_phred_coding = functionalAnnotationArray[pos++];
        String Eigen_PC_raw_coding = functionalAnnotationArray[pos++];
        String Eigen_PC_raw_coding_rankscore = functionalAnnotationArray[pos++];
        String Eigen_pred_coding = functionalAnnotationArray[pos++];
        String Eigen_raw_coding = functionalAnnotationArray[pos++];
        String Eigen_raw_coding_rankscore = functionalAnnotationArray[pos++];
        // Ensembl_geneid | Ensembl_proteinid | Ensembl_transcriptid
        String Ensembl_geneid = functionalAnnotationArray[pos++];
        String geneEns = null;
        if (!Ensembl_geneid.isEmpty()) {
            dbExt[ENSEMBL] = true;
            addStrToListSet(dbExtId, ENSEMBL, Ensembl_geneid);
            addStrToJsonObject("geneAffectedId", toStringList(dbExtId.get(ENSEMBL)), funcAnnotation, false);
            geneEns = Ensembl_geneid.split("&")[0];
        }

        //addStrToJsonObject("Ensembl_geneid_full", Ensembl_geneid, funcAnnotation, false);
        // 69 -
        String Ensembl_proteinid = functionalAnnotationArray[pos++];
        //addStrToJsonObject("Ensembl_proteinid_full", Ensembl_proteinid, funcAnnotation, false);
        String Ensembl_transcriptid = functionalAnnotationArray[pos++];
        //addStrToJsonObject("Ensembl_transcriptid_full", Ensembl_transcriptid, funcAnnotation, false);




        addNumberToJsonObject("AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        addNumberToJsonObject("AFR_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("AFR_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        addNumberToJsonObject("AMR_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("AMR_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        addNumberToJsonObject("Adj_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("Adj_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        // 79
        addNumberToJsonObject("EAS_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("EAS_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        addNumberToJsonObject("FIN_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("FIN_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        addNumberToJsonObject("NFE_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("NFE_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        addNumberToJsonObject("SAS_AC", functionalAnnotationArray[pos++] , frequencyExAc, true, 'l');
        addNumberToJsonObject("SAS_AF", functionalAnnotationArray[pos++] , frequencyExAc, true, 'f');
        String ExAC_nonTCGA_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_AF = functionalAnnotationArray[pos++];
        // 89
        String ExAC_nonTCGA_AFR_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_AFR_AF = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_AMR_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_AMR_AF = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_Adj_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_Adj_AF = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_EAS_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_EAS_AF = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_FIN_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_FIN_AF = functionalAnnotationArray[pos++];
        // 99
        String ExAC_nonTCGA_NFE_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_NFE_AF = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_SAS_AC = functionalAnnotationArray[pos++];
        String ExAC_nonTCGA_SAS_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_AFR_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_AFR_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_AMR_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_AMR_AF = functionalAnnotationArray[pos++];
        // 109
        String ExAC_nonpsych_Adj_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_Adj_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_EAS_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_EAS_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_FIN_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_FIN_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_NFE_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_NFE_AF = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_SAS_AC = functionalAnnotationArray[pos++];
        String ExAC_nonpsych_SAS_AF = functionalAnnotationArray[pos++];
        // 119
        String FATHMM_converted_rankscore = functionalAnnotationArray[pos++];
        String FATHMM = functionalAnnotationArray[pos++];
        String FATHMM_score = functionalAnnotationArray[pos++];
        String GENCODE_basic = functionalAnnotationArray[pos++];
        String GERPpp_NR = functionalAnnotationArray[pos++];
        String GERPpp_RS = functionalAnnotationArray[pos++];
        addNumberToJsonObject("GERP", GERPpp_RS, conservation, true, 'f');
        String GERPpp_RS_rankscore = functionalAnnotationArray[pos++];
        String GM12878_confidence_value = functionalAnnotationArray[pos++];
        String GM12878_fitCons_rankscore = functionalAnnotationArray[pos++];
        String GM12878_fitCons_score = functionalAnnotationArray[pos++];
        // 129
        String GTEx_V7_gene = functionalAnnotationArray[pos++];
        String GTEx_V7_tissue = functionalAnnotationArray[pos++];
        String GenoCanyon_rankscore = functionalAnnotationArray[pos++];
        String GenoCanyon_score = functionalAnnotationArray[pos++];
        String Geuvadis_eQTL_target_gene = functionalAnnotationArray[pos++];
        String H1_hESC_confidence_value = functionalAnnotationArray[pos++];
        String H1_hESC_fitCons_rankscore = functionalAnnotationArray[pos++];
        String H1_hESC_fitCons_score = functionalAnnotationArray[pos++];
        String HGVSc_ANNOVAR = functionalAnnotationArray[pos++];
        String HGVSc_VEP = functionalAnnotationArray[pos++];
        // 139
        String HGVSc_snpEff = functionalAnnotationArray[pos++];
        String HGVSp_ANNOVAR = functionalAnnotationArray[pos++];
        String HGVSp_VEP = functionalAnnotationArray[pos++];
        String HGVSp_snpEff = functionalAnnotationArray[pos++];
        String HUVEC_confidence_value = functionalAnnotationArray[pos++];
        String HUVEC_fitCons_rankscore = functionalAnnotationArray[pos++];
        String HUVEC_fitCons_score = functionalAnnotationArray[pos++];
        String Interpro_domain = functionalAnnotationArray[pos++];
        String LINSIGHT = functionalAnnotationArray[pos++];
        String LINSIGHT_rankscore = functionalAnnotationArray[pos++];
        // 149
        String LRT_Omega = functionalAnnotationArray[pos++];
        String LRT_converted_rankscore = functionalAnnotationArray[pos++];
        String LRT_pred = functionalAnnotationArray[pos++];
        String LRT_score = functionalAnnotationArray[pos++];
        String M_CAP_pred = functionalAnnotationArray[pos++];
        String M_CAP_rankscore = functionalAnnotationArray[pos++];
        String M_CAP_score = functionalAnnotationArray[pos++];
        String MPC_rankscore = functionalAnnotationArray[pos++];
        String MPC_score = functionalAnnotationArray[pos++];
        String MVP_rankscore = functionalAnnotationArray[pos++];
        // 159
        String MVP_score = functionalAnnotationArray[pos++];
        String MetaLR_pred = functionalAnnotationArray[pos++];
        String MetaLR_rankscore = functionalAnnotationArray[pos++];
        String MetaLR_score = functionalAnnotationArray[pos++].trim();
        String MetaSVM_pred = functionalAnnotationArray[pos++].trim();
        String MetaSVM_rankscore = functionalAnnotationArray[pos++].trim();
        String MetaSVM_score = functionalAnnotationArray[pos++].trim();
        String MutPred_AAchange = functionalAnnotationArray[pos++].trim();
        String MutPred_Top5features = functionalAnnotationArray[pos++].trim();
        String MutPred_protID = functionalAnnotationArray[pos++].trim();
        // 169
        String MutPred_rankscore = functionalAnnotationArray[pos++].trim();
        String MutPred_score = functionalAnnotationArray[pos++].trim();
        String MutationAssessor_pred = functionalAnnotationArray[pos++].trim();
        String MutationAssessor_rankscore = functionalAnnotationArray[pos++].trim();
        String MutationAssessor_score = functionalAnnotationArray[pos++].trim();
        String MutationTaster_AAE = functionalAnnotationArray[pos++].trim();
        String MutationTaster_converted_rankscore = functionalAnnotationArray[pos++].trim();
        String MutationTaster_model = functionalAnnotationArray[pos++].trim();
        String MutationTaster_pred = functionalAnnotationArray[pos++].trim();
        String MutationTaster_score = functionalAnnotationArray[pos++].trim();
        // 179
        String PROVEAN_converted_rankscore = functionalAnnotationArray[pos++].trim();
        String PROVEAN_pred = functionalAnnotationArray[pos++].trim();
        String PROVEAN_score = functionalAnnotationArray[pos++].trim();
        String Polyphen2_HDIV = functionalAnnotationArray[pos++].trim();
        String Polyphen2_HDIV_rankscore = functionalAnnotationArray[pos++].trim();
        String Polyphen2_HDIV_score = functionalAnnotationArray[pos++];
        String Polyphen2_HVAR_pred = functionalAnnotationArray[pos++].trim();
        String Polyphen2_HVAR_rankscore = functionalAnnotationArray[pos++].trim();
        String Polyphen2_HVAR_score = functionalAnnotationArray[pos++];
        String PrimateAI_pred = functionalAnnotationArray[pos++].trim();
        // 189
        String PrimateAI_rankscore = functionalAnnotationArray[pos++].trim();
        String PrimateAI_score = functionalAnnotationArray[pos++].trim();
        String REVEL_rankscore = functionalAnnotationArray[pos++].trim();
        String REVEL_score = functionalAnnotationArray[pos++].trim();
        String Reliability_index = functionalAnnotationArray[pos++].trim();
        String SIFT4G_converted_rankscore = functionalAnnotationArray[pos++].trim();
        String SIFT4G_pred = functionalAnnotationArray[pos++].trim();
        String SIFT4G_score = functionalAnnotationArray[pos++].trim();
        String SIFT_converted_rankscore = functionalAnnotationArray[pos++].trim();
        String SIFT = functionalAnnotationArray[pos++];
        // 199
        String SIFT_score = functionalAnnotationArray[pos++].trim();
        String SiPhy_29way_logOdds = functionalAnnotationArray[pos++].trim();
        String SiPhy_29way_logOdds_rankscore = functionalAnnotationArray[pos++].trim();
        String SiPhy_29way_pi = functionalAnnotationArray[pos++].trim();
        String TSL = functionalAnnotationArray[pos++];
        String TWINSUK_AC = functionalAnnotationArray[pos++];
        String TWINSUK_AF = functionalAnnotationArray[pos++];
        String UK10K_AC = functionalAnnotationArray[pos++];
        String UK10K_AF = functionalAnnotationArray[pos++];
        String Uniprot_acc = functionalAnnotationArray[pos++];
        // 209
        String Uniprot_entry = functionalAnnotationArray[pos++];
        String VEP_canonical = functionalAnnotationArray[pos++];
        String VEST4_rankscore = functionalAnnotationArray[pos++];
        String VEST4_score = functionalAnnotationArray[pos++];
        String VindijiaNeandertal = functionalAnnotationArray[pos++];
        String aaalt = functionalAnnotationArray[pos++];
//        addStrToJsonObject("aaAlt", aaalt, funcAnnotation, false);
        String aapos = functionalAnnotationArray[pos++];
//        addStrToJsonObject("aaPos", functionalAnnotationArray[pos++] , funcAnnotation, false);
        String aaref = functionalAnnotationArray[pos++];
//        addStrToJsonObject("aaRef", aaref, funcAnnotation, false);
        String alt = functionalAnnotationArray[pos++];
//        addStrToJsonObject("alt", functionalAnnotationArray[pos++], funcAnnotation, false);
        String bStatistic = functionalAnnotationArray[pos++];
        // 219
        String bStatistic_rankscore = functionalAnnotationArray[pos++];
        String cds_strand = functionalAnnotationArray[pos++];
        String chr = functionalAnnotationArray[pos++];
        String clinvar_MedGen_id = functionalAnnotationArray[pos++];
        String omim = functionalAnnotationArray[pos++];
        if (addStrToJsonObject("clinvar_OMIM_id", omim, funcAnnotation, false)){
            dbExt[OMIM] = true;
            addStrToListSet(dbExtId, OMIM, omim);
        }
        String orpha = functionalAnnotationArray[pos++];
        if (addStrToJsonObject("clinvar_Orphanet_id", orpha, funcAnnotation, false)){
            dbExt[ORPHANET] = true;
            addStrToListSet(dbExtId, ORPHANET, orpha);
        }
        String clinvarClinsign = functionalAnnotationArray[pos++];
//        addStrToJsonObject("clinvar_clnsig", , funcAnnotation, false);
        if (!clinvarClinsign.isEmpty()) {
            dbExt[CLINVAR_SIG] = true;
            addStrToListSet(dbExtId, CLINVAR_SIG, clinvarClinsign);
        }
        String clinvar_hgvs = functionalAnnotationArray[pos++];
//        addStrToJsonObject("clinvar_hgvs", , funcAnnotation, false);
        String clinvar = functionalAnnotationArray[pos++];
        if ( !clinvar.isEmpty()){
            dbExt[CLINVAR] = true;
            addStrToListSet(dbExtId, CLINVAR, clinvar);
        }
        String clinvar_review = functionalAnnotationArray[pos++];
        // 229
        String clinvarTrait = functionalAnnotationArray[pos++];
        //addStrToJsonObject("clinvar_trait", , funcAnnotation, false);
        if (!clinvarTrait.isEmpty()) {
            dbExt[CLINVAR_TRAIT] = true;
            addStrToListSet(dbExtId, CLINVAR_TRAIT, clinvarTrait);
        }
        String clinvar_var_source = functionalAnnotationArray[pos++];
        String codon_degeneracy = functionalAnnotationArray[pos++];
        String codonpos = functionalAnnotationArray[pos++];
        String fathmm_MKL_coding_group = functionalAnnotationArray[pos++];
        String fathmm_MKL_coding_pred = functionalAnnotationArray[pos++];
        String fathmm_MKL_coding_rankscore = functionalAnnotationArray[pos++];
        String fathmm_MKL_coding_score = functionalAnnotationArray[pos++];
        String fathmm_XF_coding_pred = functionalAnnotationArray[pos++];
        String fathmm_XF_coding_rankscore = functionalAnnotationArray[pos++];
        // 239
        String fathmm_XF_coding_score = functionalAnnotationArray[pos++];
        String geneName = functionalAnnotationArray[pos++];
        if (addStrToJsonObject("geneAffectedSymbol", geneName, funcAnnotation, false)) {
            dbExt[GENES] = true;
            addStrToListSet(dbExtId, GENES, geneName);
        }

        addNumberToJsonObject("AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        addNumberToJsonObject("AFR_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("AFR_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');

        String gnomAD_exomes_AFR_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_AFR_nhomalt = functionalAnnotationArray[pos++];

        addNumberToJsonObject("AMR_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("AMR_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        // 249
        String gnomAD_exomes_AMR_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_AMR_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_AN = functionalAnnotationArray[pos++];

        addNumberToJsonObject("ASJ_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("ASJ_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        String gnomAD_exomes_ASJ_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_ASJ_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("EAS_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("EAS_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        String gnomAD_exomes_EAS_AN = functionalAnnotationArray[pos++];
        // 259
        String gnomAD_exomes_EAS_nhomalt = functionalAnnotationArray[pos++];

        addNumberToJsonObject("FIN_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("FIN_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        String gnomAD_exomes_FIN_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_FIN_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("NFE_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("NFE_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        String gnomAD_exomes_NFE_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_NFE_nhomalt = functionalAnnotationArray[pos++];
        // 269
        addNumberToJsonObject("POPMAX_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        // 269
        addNumberToJsonObject("POPMAX_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        String gnomAD_exomes_POPMAX_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_POPMAX_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("SAS_AC", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'l');
        addNumberToJsonObject("SAS_AF", functionalAnnotationArray[pos++] , frequencyGnomadEx, true, 'f');
        String gnomAD_exomes_SAS_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_SAS_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AFR_AC = functionalAnnotationArray[pos++];
        // 279
        String gnomAD_exomes_controls_AFR_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AFR_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AFR_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AMR_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AMR_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AMR_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AMR_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_ASJ_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_ASJ_AF = functionalAnnotationArray[pos++];
        // 289
        String gnomAD_exomes_controls_ASJ_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_ASJ_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_EAS_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_EAS_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_EAS_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_EAS_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_FIN_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_FIN_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_FIN_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_FIN_nhomalt = functionalAnnotationArray[pos++];
        // 299
        String gnomAD_exomes_controls_NFE_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_NFE_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_NFE_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_NFE_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_POPMAX_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_POPMAX_AF = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_POPMAX_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_POPMAX_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_SAS_AC = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_SAS_AF = functionalAnnotationArray[pos++];
        // 309
        String gnomAD_exomes_controls_SAS_AN = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_SAS_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_controls_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_exomes_flag = functionalAnnotationArray[pos++];
        String gnomAD_exomes_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("AC", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'l');
        addNumberToJsonObject("AF", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'f');
        addNumberToJsonObject("AFR_AC", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'l');
        addNumberToJsonObject("AFR_AF", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'f');
        String gnomAD_genomes_AFR_AN = functionalAnnotationArray[pos++];
        // 319
        String gnomAD_genomes_AFR_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("AMR_AC", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'l');
        addNumberToJsonObject("AMR_AF", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'f');
        String gnomAD_genomes_AMR_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_AMR_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_ASJ_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_ASJ_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_ASJ_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_ASJ_nhomalt = functionalAnnotationArray[pos++];
        // 329
        addNumberToJsonObject("EAS_AC", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'l');
        addNumberToJsonObject("EAS_AF", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'f');
        String gnomAD_genomes_EAS_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_EAS_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_FIN_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_FIN_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_FIN_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_FIN_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("NFE_AC", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'l');
        addNumberToJsonObject("NFE_AF", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'f');
        // 339
        String gnomAD_genomes_NFE_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_NFE_nhomalt = functionalAnnotationArray[pos++];
        addNumberToJsonObject("POPMAX_AC", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'l');
        addNumberToJsonObject("POPMAX_AF", functionalAnnotationArray[pos++] , frequencyGnomadGen, true, 'f');
        String gnomAD_genomes_POPMAX_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_POPMAX_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AFR_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AFR_AF = functionalAnnotationArray[pos++];
        // 349
        String gnomAD_genomes_controls_AFR_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AFR_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AMR_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AMR_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AMR_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AMR_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_ASJ_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_ASJ_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_ASJ_AN = functionalAnnotationArray[pos++];
        // 359
        String gnomAD_genomes_controls_ASJ_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_EAS_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_EAS_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_EAS_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_EAS_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_FIN_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_FIN_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_FIN_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_FIN_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_NFE_AC = functionalAnnotationArray[pos++];
        // 369
        String gnomAD_genomes_controls_NFE_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_NFE_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_NFE_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_POPMAX_AC = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_POPMAX_AF = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_POPMAX_AN = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_POPMAX_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_controls_nhomalt = functionalAnnotationArray[pos++];
        String gnomAD_genomes_flag = functionalAnnotationArray[pos++];
        String gnomAD_genomes_nhomalt = functionalAnnotationArray[pos++];
        // 379
        String hg18_chr = functionalAnnotationArray[pos++];
        String hg18_pos_1based = functionalAnnotationArray[pos++];
        String hg19_chr = functionalAnnotationArray[pos++];
        String hg19_pos_1based = functionalAnnotationArray[pos++];
        String integrated_confidence_value = functionalAnnotationArray[pos++];
        String integrated_fitCons_rankscore = functionalAnnotationArray[pos++];
        String integrated_fitCons_score = functionalAnnotationArray[pos++];
        String phastCons100way_vertebrate = functionalAnnotationArray[pos++];
        String phastCons100way_vertebrate_rankscore = functionalAnnotationArray[pos++];
        String phastCons17way_primate = functionalAnnotationArray[pos++];
        // 389
        String phastCons17way_primate_rankscore = functionalAnnotationArray[pos++];
        addNumberToJsonObject("PhastCons", phastCons17way_primate_rankscore, conservation, true, 'f');
        String phastCons30way_mammalian = functionalAnnotationArray[pos++];
        String phastCons30way_mammalian_rankscore = functionalAnnotationArray[pos++];
        String phyloP100way_vertebrate = functionalAnnotationArray[pos++];
        String phyloP100way_vertebrate_rankscore = functionalAnnotationArray[pos++];
        String phyloP17way_primate = functionalAnnotationArray[pos++];
        String phyloP17way_primate_rankscore = functionalAnnotationArray[pos++];
        addNumberToJsonObject("PhyloP", phyloP17way_primate_rankscore, conservation, true, 'f');
        String phyloP30way_mammalian = functionalAnnotationArray[pos++];
        String phyloP30way_mammalian_rankscore = functionalAnnotationArray[pos++];
        String pos_1_based = functionalAnnotationArray[pos++];
        // 399
        String ref = functionalAnnotationArray[pos++];
//        addStrToJsonObject("ref", ref, funcAnnotation, false);
        String refcodon = functionalAnnotationArray[pos++];
//        addStrToJsonObject("ref_codon", functionalAnnotationArray[pos++], funcAnnotation, false);

        String rs_dbSNP151 = functionalAnnotationArray[pos];
        if (!rs_dbSNP151.isEmpty()) {
            dbExt[DBSNP] = true;
            addStrToListSet(dbExtId, DBSNP, rs_dbSNP151);
        }
        // 408


        /* population frequencies Json Obj */

        // Create a map of Map<TranscriptIdFull, deStructuredTranscript>
        /*
        public Transcript(  ) {
         */

        if (deStructuredTranscript.isEmpty() && !Ensembl_transcriptid.trim().isEmpty()) {

            String[] ensemblTranscriptIdArray = Ensembl_transcriptid.split("&");
            int length = ensemblTranscriptIdArray.length;
            String[] ensemblProteinIdArray = Ensembl_proteinid.split("&");
            String[] aaPosArray = aapos.split("&");
            String[] FATHMM_scoreArray = (FATHMM_score.isEmpty()) ? fillArray(length) : FATHMM_score.split("&");
            String[] FATHMMArray = (FATHMM.isEmpty()) ? fillArray(length) : FATHMM.split("&");
            String[] SIFTArray = SIFT.split("&");
            String[] SIFT_scoreArray = SIFT_score.split("&");
            String[] MutationAssessor_predArray = MutationAssessor_pred.split("&");
            String[] MutationAssessor_scoreArray = MutationAssessor_score.split("&");
            String[] Polyphen2_HDIVArray = Polyphen2_HDIV.split("&");
            String[] Polyphen2_HDIV_scoreArray = Polyphen2_HDIV_score.split("&");
            String[] Polyphen2_HVAR_predArray = Polyphen2_HVAR_pred.split("&");
            String[] Polyphen2_HVAR_scoreArray = Polyphen2_HVAR_score.split("&");
            //String[] LRT_predArray = LRT_pred.split("&");
            //String[] LRT_scoreArray = LRT_score.split("&");




            for (int i=0; i < ensemblTranscriptIdArray.length; i++) {
                String tid = ensemblTranscriptIdArray[i];
                try {
                    deStructuredTranscript.put(tid, new Transcript(tid, aaPosArray[i], FATHMMArray[i], SIFTArray[i],
                            ensemblProteinIdArray[i], Polyphen2_HVAR_scoreArray[i], MutationAssessor_scoreArray[i],
                            Polyphen2_HDIVArray[i], Polyphen2_HDIV_scoreArray[i], LRT_pred, FATHMM_scoreArray[i],
                            MutationAssessor_predArray[i], Polyphen2_HVAR_predArray[i], LRT_score, SIFT_scoreArray[i]));
                } catch (ArrayIndexOutOfBoundsException aioe) {
                    System.err.println("something can be empty");
                    aioe.printStackTrace();
                    System.exit(1);
                }
            }
        }

        if (deStructuredTranscript.containsKey(featureId)) {
            Transcript transcript = deStructuredTranscript.get(featureId);
            String aaChange = aaref + transcript.getAaPosition() + aaalt;
            addStrToJsonObject("aaChange", aaChange , funcAnnotation, false);


            prediction = transcript.getPrediction();
            if ( (boolean) prediction.get("available") ) {
                funcAnnotation.put("predictions", prediction);
            }

        }


        cdnaChange = ref + cdnaPos + alt;
        addStrToJsonObject("cdnaChange", cdnaChange , funcAnnotation, false);

        frequencies.put("1000Gp3", frequency1000Gp3);
        frequencies.put("ExAc", frequencyExAc);
        frequencies.put("gnomAD_exomes", frequencyGnomadEx);
        frequencies.put("gnomAD_genomes", frequencyGnomadGen);
        frequencies.put("ESP6500", frequencyEsp6500);
        funcAnnotation.put("frequencies", frequencies);
        funcAnnotation.put("conservationsScores", conservation);

        if (geneEns != null) {
            geneSet.add(new Gene(geneEns, geneName, biotype));
        }

        return funcAnnotation;

    }

    static boolean addNumberToJsonObject(String name, String var, JSONObject jsonObject, boolean withAvailability, char type) {
        boolean avail = false;
        if (var != null && !var.trim().isEmpty() && !".".equalsIgnoreCase(var)) {
            try {
                if (type == 'l') {
                    jsonObject.put(name, new BigDecimal(var).longValue());
                } else {
                    jsonObject.put(name, new BigDecimal(var).doubleValue());
                }
                avail = true;
                if (withAvailability) jsonObject.put("available", avail);
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
                toPrint = true;
                if (withAvailability) jsonObject.put("available", avail);
            }
        } else if (withAvailability) {
            jsonObject.put("available", avail);

        }
        return avail;
    }

    private static boolean addBooleanToJsonObject(String name, String var, JSONObject jsonObject, boolean withAvailability) {
        boolean avail = false;
        if (var.length()>0 && !".".equalsIgnoreCase(var)) {
            if ("YES".equalsIgnoreCase(var)) {
                jsonObject.put(name, true);
                avail = true;
            } else {
                jsonObject.put(name, false);
                avail = true;
            }
            if (withAvailability) jsonObject.put("available", avail);
        } else if (withAvailability) {
            jsonObject.put("available", avail);
            jsonObject.put(name, false);
        } else {
            jsonObject.put(name, false);
        }
        return avail;
    }

    static boolean addStrToJsonObject(String name, String var, JSONObject jsonObject, boolean withAvailability) {
        boolean avail = false;
        if (var != null && var.length()>0 && !".".equalsIgnoreCase(var)) {
            jsonObject.put(name, var);
            avail = true;
            if (withAvailability) jsonObject.put("available", avail);
        } else if (withAvailability) {
            jsonObject.put("available", avail);
        }
        return avail;

    }
//    private static boolean addStrToSetObject(String name, String var, JSONObject jsonObject, boolean withAvailability) {
//        boolean avail = false;
//        if (var.length()>0 && !".".equalsIgnoreCase(var)) {
//            jsonObject.put(name, var);
//            avail = true;
//            if (withAvailability) jsonObject.put("available", avail);
//        } else if (withAvailability) {
//            jsonObject.put("available", avail);
//        }
//        return avail;
//
//    }

    public static String zygosity(String gt) {
        char[] gtA = gt.toCharArray();
        if (gtA[0] == '.' || gtA[2] == '.') {
            return "UNK";
        }
        if (gtA.length == 3) {
            // Homo
            if ( gtA[0] == gtA[2] ) {
                if ( gtA[0] == '0') {
                    return "HOM REF";
                } else {
                    return "HOM ALT";
                }
            // not equal
            } else if (gtA[0] == '0' ) {
                return "HET REF";
            } else if (gtA[0] == '1' ) {
                return "HET ALT";
            }
        }
        return "UNK";

    }

    static int getImpactScore(String impact) {
        if (impact == null) return 0;
        switch (impact.toUpperCase()) {
            case "HIGH" : return 4;
            case "MODERATE" : return 3;
            case "LOW" : return 2;
            case "MODIFIER" : return 1;
            default: return 0;

        }
    }


    static Properties getPropertiesFromFile(String filename) {
        Properties prop = new Properties();

        try (BufferedReader buf = new BufferedReader(new FileReader(filename))) {
            prop.load(buf);
        } catch (IOException e) {
            e.printStackTrace();
        }


        return prop;
    }

    static JSONArray addDonorArrayToDonorArray(JSONArray previous, JSONArray newOne) {

        for (int i = 0; i<newOne.length(); i++) {
            previous.put(newOne.get(i));
        }
        return previous;

    }

    private static void addStrToListSet(List<Set<String>> setList, int position, String value) {

        // Extract the proper set
        Set<String> extractedSet = setList.get(position);
        String[] splitedStr = value.split("&");
        Collections.addAll(extractedSet, splitedStr);
        //extractedSet.add(value);


    }

    private static String toStringList(Set<String> setStr) {
        return setStr.stream().reduce("", (x,y) -> (x.trim().isEmpty() ? x : x + ",") + y);
    }


    private static void addSetsToArrayToObj(List<Set<String>> setList, int position, JSONObject jsonObject, String name, String objName) {
        JSONArray newIdArray = new JSONArray();
        for (String id: setList.get(position)) {
            newIdArray.put(id);
        }
        if (newIdArray.length() > 0 ) {
            jsonObject.put(name, newIdArray);
        }
    }


    private static void addGeneSetsToObj(String ensId, JSONObject jsonObject) {

        //countRedisCalls++;
        Set<String> geneSets = getMembersForEnsId(ensId);
        JSONArray hpoGeneSets = new JSONArray();
        JSONArray orphanetGeneSets = new JSONArray();
        JSONArray alias = new JSONArray();
        JSONArray radboudumc = new JSONArray();

        geneSets.forEach( member -> {

            if (member.startsWith("HP:")) {
                hpoGeneSets.put(member);

            } else if (member.startsWith("Orph:")) {
                orphanetGeneSets.put(member);
            } else if (member.startsWith("alias:")) {
                alias.put(member.replace("alias:", ""));
            } else if (member.startsWith("Rad:")) {
                radboudumc.put(member);
            }

            jsonObject.put("hpo", hpoGeneSets);
            jsonObject.put("orphanet", orphanetGeneSets);
            jsonObject.put("alias", alias);
            jsonObject.put("radboudumc", radboudumc);

        });
    }

    private static String[] fillArray(int size) {
        String[] array = new String[size];
        Arrays.fill(array, "");
        return array;
    }

}