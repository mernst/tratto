package star.tratto;

import org.junit.jupiter.api.Test;
import star.tratto.data.OracleDP2TokenDPs;
import star.tratto.data.TokenDPType;

import java.io.IOException;

public class TokensDatasetTest {

    @Test
    public void tokensDatasetTest() throws IOException {
        TokensDataset.ORACLES_DATASET_FOLDER = "src/test/resources/oracles-dataset/";
        TokensDataset.TOKENS_DATASET_FOLDER = "src/test/resources/tokens-dataset/";
        TokensDataset.DATASET_TYPE = TokenDPType.TOKEN;
        OracleDP2TokenDPs.CRASH_WRONG_ORACLE = true;
        TokensDataset.main(new String[] {});

        /*
         * TODO: Possible assertions:
         *  - For each unique oracleSoFar, there's always a row whose label is true
         *  - For each unique oracleSoFar, there are no rows with repeated tokens
         *  - Assert the exact values of certain rows, especially one of each type (this, methodResultID, etc.)
         *  - Assert exact suggested tokens after certain oraclesSoFar
         */
    }
}
