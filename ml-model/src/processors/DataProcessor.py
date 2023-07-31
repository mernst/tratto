import os
from typing import Type, Dict, List, Tuple
import pandas as pd
from pandas import DataFrame
import numpy as np
import torch
import random
import math
from torch.utils.data import TensorDataset
from sklearn.model_selection import train_test_split, StratifiedKFold
from transformers import PreTrainedTokenizer
from src.types.ClassificationType import ClassificationType
from src.types.DatasetType import DatasetType
from src.types.TransformerType import TransformerType
from src.types.TrattoModelType import TrattoModelType
from src.utils import utils


class DataProcessor:
    """
    The *DataProcessor* class takes care of pre-processing the raw datasets to create the final tokenized datasets.
    The datasets generated by the class can be feed to the *Dataset* and *DataLoader* PyTorch classes to easily load
    the data and prepare the batches to feed the model.

    Parameters
    ----------
    d_path: str
        The path to the dataset.
    test_ratio: float
        The ratio of the original dataset reserved for testing (acceptable range of values: from 0.0 to 1.0).
    tokenizer: Type[PreTrainedTokenizer]
        The instance of tokenizer used to tokenize the input dataset.
    transformerType: Type[TransformerType]
        The classificator type (encoder or decoder)
    classification_type: Type[ClassificationType]
        Category predictions or label predictions.
    tratto_model_type: Type[TrattoModelType]
        TokenClasses or tokenValues model.
    folds: int
        The number of folds, if cross-validation is performed (folds > 1). Set to 1 by default (no cross-validation)

    Attributes
    ----------
    _d_path: str
        The path to the dataset.
    _tokenizer  : Type[PreTrainedTokenizer]
        The instance of tokenizer used to tokenize the input dataset.
    _test_ratio: float
        The ratio of the original dataset reserved for testing (acceptable range of values: from 0.0 to 1.0).
    _df_dataset: Type[DataFrame]
        The input dataset containing all the datapoints for training, validation, and test.
    _src : List[str]
        The list of input datapoints (concatenated strings, with separator token). This source list is used to train
        and validate the model. Initially set to None.
    _tgt : List[int]
        The list of expected class values (in one-shot vector format) for each datapoint in input to the model.
        This target list is used to train and validate the model. Initially set to None.
    _src_test : List[str]
        The list of input datapoints (concatenated strings, with separator token). This source list is used to test
        the model. Initially set to None.
    _tgt_test : List[int]
        The list of expected values (in one-shot vector format) for a subset of datapoints in input to the model.
        This target list is used to test the model. Initially set to None.
    _classification_type: Type[ClassificationType]
        Category predictions or label predictions.
    _transformer_type: Type[TransformerType]
        The classificatorType (encoder or decoder).
    _tratto_model_type: Type[TrattoModelType]
        TokenClasses or tokenValues model.
    _folds: int
        The number of folds, if cross-validation is performed (folds > 1). Set to 1 by default (no cross-validation).
    _processed_dataset: Dict[str,List[List[Union[str,int]]]]
        Dictionary of the processed dataset:
            - train: contains a list (each element representing a fold) of datasets for the training phase, after the
              original dataset has been split (according to *validation_ratio* value).
            - val: contains a list (each element representing a fold) of datasets for the validation phase, after the
              original dataset has been split (according to *validation_ratio* value).
            - test: contains a list representing the dataset for the testing phase, after it has been tokenized.
            - t_train: contains a list (each element representing a fold) of datasets for the training phase, after the
              original dataset has been split (according to *validation_ratio* value).
            - t_val: contains a list (each element representing a fold) of datasets for the validation phase, after the
              original dataset has been split (according to *validation_ratio* value), and tokenized.
            - t_test: contains a list representing the dataset for the testing phase, after it has been tokenized.
    """

    def __init__(
            self,
            d_path: str,
            test_ratio: float,
            tokenizer: PreTrainedTokenizer,
            transformerType: Type[TransformerType],
            classification_type: ClassificationType,
            tratto_model_type: Type[TrattoModelType],
            folds: int = 1
    ):
        self._d_path = d_path
        self._tokenizer = tokenizer
        self._test_ratio = test_ratio
        self._df_dataset = self._load_dataset(d_path)
        self._src = None
        self._tgt = None
        self._src_test = None
        self._tgt_test = None
        self._classification_type = classification_type
        self._transformer_type = transformerType
        self._tratto_model_type = tratto_model_type
        self._folds = folds
        self._processed_dataset = {
            "train": [],
            "val": [],
            "test": [],
            "t_train": [],
            "t_val": [],
            "t_test": []
        }

    def compute_weights(
            self,
            column_name: str
    ):
        """
        The method computes the weights to assign to each value of the list passed to the function. The weights are
        used to assign a different importance to each value of the list (targets), in the computation of the loss of
        the classification task, when the dataset is imbalanced.

        Parameters
        ----------
        column_name: str
            The name of the column of the dataset to process

        Returns
        -------
        values_weights : List[float]
            A list containing the weights for each different value of the column within the dataset. The length of
            the list is equal to the number of unique values in the column processed.
        """
        # Get the list of unique labels and the count the occurrences of each class
        unique_classes, class_counts = np.unique(self._df_dataset[column_name], return_counts=True)
        # Calculate the inverse class frequencies
        total_samples = np.sum(class_counts)
        class_frequencies = class_counts / total_samples
        class_weights = 1.0 / class_frequencies
        # Normalize the class weights
        class_weights /= np.sum(class_weights)
        # Return the computed weights
        return class_weights

    def get_encoder_labels_ids(self):
        """
        The method computes the dictionary of target labels of the classification model, where each key represents the
        name of a target label, while the corresponding value is a numerical identifier representing the index of
        the one-shot vector representing the label, with value equals to 1.0.

        Returns
        -------
        The dictionary of labels. The keys strings representing the name of the corresponding target label, while the 
        values are strings representing the name of the corresponding target label.
        """
        return {k: i for i, k in self.get_encoder_ids_labels().items()}

    def get_encoder_ids_labels(
        self
    ):
        """
        The method computes the dictionary of target labels of the classification model, where each value represents the
        name of a target label, while the corresponding key element is a numerical identifier representing the index of
        the one-shot vector representing the label, with value equals to 1.0.

        Returns
        -------
        The dictionary of labels. The keys are numerical identifiers (int), while the values are strings representing the
        name of the corresponding target label.
        """
        if self._classification_type == ClassificationType.CATEGORY_PREDICTION:
            tgt_column_name = "tokenClass" if self._tratto_model_type == TrattoModelType.TOKEN_CLASSES else "token"
        else:
            tgt_column_name = "label"
        ids_tgt_labels_dict = {i: str(k) for i, k in enumerate(sorted(list(self._df_dataset[tgt_column_name].unique())))}
        return ids_tgt_labels_dict

    def get_num_labels(self):
        """
        Get the number of unique values of target labels.

        Returns
        -------
        An integer representing the number of unique values of labels.
        """
        num_labels = len(self.get_encoder_ids_labels())
        return num_labels

    def get_encoder_classes_ids(self):
        """
        The method computes the dictionary of target classes of the classification model (encoder), where the key is the
        name of a class, while the value element is a numerical identifier representing the codification of a
        corresponding class.

        Returns
        -------
        The dictionary of classes. The keys are strings representing the name of a class (int), while the values are the
        corresponding numeric codification.
        """
        if self._tratto_model_type == TrattoModelType.TOKEN_CLASSES:
            return {k: i for i, k in enumerate(sorted(list(self._df_dataset["tokenClass"].unique())))}
        else:
            return {k: i for i, k in enumerate(sorted(list(self._df_dataset["token"].unique())))}

    def get_encoder_ids_classes(self):
        """
        The method computes the dictionary of classes of the classification model, a numerical identifier representing
        the index of the one-shot vector representing the class while the value element is the name of the corresponding
        target class.

        Returns
        -------
        The dictionary of classes. The keys are numeric identifiers (integer) representing the codification value of a
        class (int), while the values are the name of the corresponding target class.
        """
        return {i: k for k, i in self.get_encoder_classes_ids().items()}

    def get_tgt_classes_size(self):
        """
        The method computes the number of classes of the classification model.

        Returns
        -------
        The number of target classes of the classification model.
        """
        tgt_column_name = "tokenClass" if self._classification_type == ClassificationType.CATEGORY_PREDICTION else "label"
        unique_values = np.unique(self._df_dataset[tgt_column_name])
        classes_size = len(unique_values)
        if classes_size == 0:
            print("[Warn] - classes size is 0")
        return classes_size

    def get_tokenized_dataset(
            self,
            d_type: Type[DatasetType],
            fold_idx: int = 0
    ):
        """
        The method returns the processed tokenized (training or validation) dataset of the requested fold.
        If cross-validation is not performed the first and only fold is returned (by default, the fold index is equal
        to 0, therefore no crossa-validation is considered).

        Parameters
        ----------
        d_type: DatasetType
            The dataset type that the method must return
                - DatasetType.TRAINING for the training dataset
                - DatasetType.VALIDATION for the validation dataset
                - DatasetType.TEST for the test dataset
        fold_idx: int
            The index of the fold (0 by default, if no cross-validation is performed)

        Returns
        -------
        t_t_dataset : TensorDataset
            A PyTorch TensorDataset composed of four tensors stack:
                - the first tensor stack representing the stack of tokenized input
                  datapoints of the selected dataset
                - the second tensor stack representing the stack of attention masks
                  (each index corresponding to the index of the input datapoints in
                  the first tensor)
                - the third tensor stack representing the list of expected target
                  outputs (each index corresponding to the index of the input
                  datapoints in the first tensor)
                - the fourth tensor stack representing the list of expected target
                  labels (each index corresponding to the index of the input
                  datapoints in the first tensor)
        """
        # Select the tokenized dataset
        if d_type == DatasetType.TRAINING:
            t_dataset = self._processed_dataset["t_train"][fold_idx]
        elif d_type == DatasetType.VALIDATION:
            t_dataset = self._processed_dataset["t_val"][fold_idx]
        elif d_type == DatasetType.TEST:
            t_dataset = self._processed_dataset["t_test"]
        else:
            raise Exception(f"Unrecognized DataType value: {d_type}")
        # Generate the tensor dataset from the list of tokenized datapoints
        t_t_dataset = TensorDataset(*t_dataset)
        # Return the dataset
        return t_t_dataset

    def processing(self):
        """
        The method processes the original datasets to generate the training, validation, and test datasets to train and
        test the model. If the attribute {@code self.folds} is greater than 1, the datasets are generated to perform
        cross-validation (A number of sets equal to {@code self.folds} are generated). Otherwise, a single set of datasets
        is generated.

        """
        # Create the cross-validation splitter, if the attribute folds is greater than 1
        if self._folds > 1:
            cross_validation = StratifiedKFold(n_splits=self._n_split, shuffle=True, random_state=42)
            print(f"        Generating {self._n_split} folds for cross-validation.")
            t_v_cross_datasets = cross_validation.split(self._src, np.array([np.array(dp) for dp in self._tgt]))
            for fold, (t_fold_indices, v_fold_indices) in enumerate(t_v_cross_datasets):
                print(f"            Processing fold {fold + 1}.")
                # Split the dataset into training and validation source and target sets for the current fold
                t_src_fold_data = [self._src[i] for i in t_fold_indices]
                t_tgt_fold_data = [self._tgt[i] for i in t_fold_indices]
                v_src_fold_data = [self._src[i] for i in v_fold_indices]
                v_tgt_fold_data = [self._tgt[i] for i in v_fold_indices]
                t_fold_dataset = (t_src_fold_data, t_tgt_fold_data)
                v_fold_dataset = (v_src_fold_data, v_tgt_fold_data)
                # Tokenize training and validation datasets of the current fold
                t_t_fold_dataset = self._tokenize_dataset(t_fold_dataset)
                t_v_fold_dataset = self._tokenize_dataset(v_fold_dataset)
                # Append datasets of the current fold to the corresponding training and validation processes datasets
                self._processed_dataset["train"].append(t_fold_dataset)
                self._processed_dataset["val"].append(v_fold_dataset)
                # Append tokenized datasets of the current fold to the corresponding training and validation processes
                # tokenized datasets
                self._processed_dataset["t_train"].append(t_t_fold_dataset)
                self._processed_dataset["t_val"].append(t_v_fold_dataset)
        else:
            # Split the original dataset in training and test sets
            t_src_data, v_src_data, t_tgt_data, v_tgt_data = train_test_split(self._src, self._tgt, test_size=self._test_ratio)  # , stratify=self._tgt)
            # Generate training and validation sets
            t_dataset = (t_src_data, t_tgt_data)
            v_dataset = (v_src_data, v_tgt_data)
            # Tokenize training and validation datasets of the current fold
            t_t_dataset = self._tokenize_dataset(t_dataset)
            t_v_dataset = self._tokenize_dataset(v_dataset)
            # Append datasets of the current fold to the corresponding training and validation processes datasets
            self._processed_dataset["train"].append(t_dataset)
            self._processed_dataset["val"].append(v_dataset)
            # Append tokenized datasets of the current fold to the corresponding training and validation processes
            # tokenized datasets
            self._processed_dataset["t_train"].append(t_t_dataset)
            self._processed_dataset["t_val"].append(t_v_dataset)
        # Generate test dataset
        test_dataset = (self._src_test, self._tgt_test)
        # Assign the test dataset to the processed datasets
        self._processed_dataset["test"] = (self._src_test, self._tgt_test)
        # Assign the tokenized test dataset to the processed tokenized datasets
        self._processed_dataset["t_test"] = self._tokenize_dataset(test_dataset)

    def pre_processing(
            self
    ):
        """
        The method pre-processes the loaded dataset that will be used to train and test the model.
        """
        # Drop column id (it is not relevant for training the model)
        self._df_dataset = self._df_dataset.drop(['id'], axis=1)
        # Map empty cells to empty strings
        self._df_dataset.fillna('', inplace=True)
        # Specify the type of each column in the dataset
        self._df_dataset = self._df_dataset.astype({
            'label': 'str',
            'oracleId': 'int64',
            'oracleType': 'string',
            'projectName': 'string',
            'packageName': 'string',
            'className': 'string',
            'javadocTag': 'string',
            'methodJavadoc': 'string',
            'methodSourceCode': 'string',
            'classJavadoc': 'string',
            'classSourceCode': 'string',
            'oracleSoFar': 'string',
            'token': 'string',
            'tokenClass': 'string',
            'tokenInfo': 'string'
        })

        # Map token class names
        _, value_mappings = utils.import_json(
            os.path.join(
                os.path.dirname(os.path.abspath(__file__)),
                '..',
                'resources',
                'tokenClassesValuesMapping.json'
            )
        )
        # If the model is for the token values consider only a subset of words
        # (the other ones will never appear whitin the dataset)
        if self._tratto_model_type == TrattoModelType.TOKEN_VALUES:
            _, ignore_values = utils.import_json(
                os.path.join(
                    os.path.dirname(os.path.abspath(__file__)),
                    '..',
                    'resources',
                    'model_values_ignore_value_mappings.json'
                )
            )
        vocab = self._tokenizer.get_vocab()
        for old_word, new_word in value_mappings.items():
            if self._tratto_model_type == TrattoModelType.TOKEN_VALUES and old_word in ignore_values:
                continue
            for new_sub_word in new_word.split("_"):
                if not new_sub_word in vocab.keys():
                    self._tokenizer.add_tokens([new_sub_word])

        # Replace the values in the DataFrame column
        self._df_dataset['tokenClass'] = self._df_dataset['tokenClass'].replace(value_mappings)

        # Pre-process the dataset, according to the Tratto model considered (tokenClasses or tokenValues).
        if self._tratto_model_type == TrattoModelType.TOKEN_CLASSES:
            # Remove part of empty oracles to balance the dataset
            df_empty_semicolon_true = self._df_dataset[
                (self._df_dataset['oracleSoFar'] == '') &
                (self._df_dataset['token'] == ';') &
                (self._df_dataset['label'] == True)
            ]
            df_not_empty_semicolon_true = self._df_dataset[
                (self._df_dataset['oracleSoFar'].str.strip() != '') |
                (self._df_dataset['token'] != ';') |
                (self._df_dataset['label'] != True)
            ]
            selected_rows = df_empty_semicolon_true.groupby('oracleId', group_keys=False).apply(lambda x: x.sample(1, random_state=42))
            self._df_dataset = pd.concat([df_not_empty_semicolon_true, selected_rows])
            self._df_dataset = self._df_dataset.reset_index(drop=True)

            # Map token classes so far to new values and transform it from array to string
            self._df_dataset["tokenClassesSoFar"] = self._df_dataset["tokenClassesSoFar"].apply(lambda x: "[ " + " ".join(random.sample([value_mappings[y] for y in x], len(x))) + " ]")
            # Compute eligible token classes
            df_eligibleTokenClasses = self._df_dataset.groupby(['oracleId', 'oracleSoFar'])['tokenClass'].unique().to_frame()
            df_eligibleTokenClasses = df_eligibleTokenClasses.rename(columns={'tokenClass': 'eligibleTokenClasses'})
            self._df_dataset = pd.merge(self._df_dataset, df_eligibleTokenClasses, on=['oracleId', 'oracleSoFar']).reset_index()
            self._df_dataset["eligibleTokenClasses"] = self._df_dataset["eligibleTokenClasses"].apply(lambda x: "[ " + " ".join(random.sample(list(x),len(x))) + " ]")
            # Set type of dataframe columns
            self._df_dataset['eligibleTokenClasses'] = self._df_dataset['eligibleTokenClasses'].astype('string')
            self._df_dataset['tokenClass'] = self._df_dataset['tokenClass'].astype('string')
            self._df_dataset['tokenClassesSoFar'] = self._df_dataset['tokenClassesSoFar'].astype('string')
            # Define the new order of columns
            new_columns_order = [
                'token', 'tokenInfo', 'tokenClass', 'oracleSoFar', 'tokenClassesSoFar', 'eligibleTokenClasses',
                'javadocTag', 'oracleType', 'packageName', 'className', 'methodSourceCode', 'methodJavadoc',
                'classJavadoc', 'classSourceCode', 'projectName', 'oracleId', 'label'
            ]
            # Reindex the DataFrame with the new order
            self._df_dataset = self._df_dataset.reindex(columns=new_columns_order)
        else:
            # Compute eligible token values
            df_eligibleTokens = self._df_dataset.groupby(['oracleId', 'oracleSoFar'])['token'].unique().to_frame()
            df_eligibleTokens = df_eligibleTokens.rename(columns={'token': 'eligibleTokens'})
            self._df_dataset = pd.merge(self._df_dataset, df_eligibleTokens, on=['oracleId', 'oracleSoFar']).reset_index()
            self._df_dataset["eligibleTokens"] = self._df_dataset["eligibleTokens"].apply(lambda x: "[ " + " ".join(random.sample(list(x),len(x))) + " ]")
            # Set type of dataframe columns
            self._df_dataset['eligibleTokens'] = self._df_dataset['eligibleTokens'].astype('string')
            # Define the new order of columns
            new_columns_order = [
                'token', 'oracleSoFar', 'eligibleTokens', 'tokenInfo', 'tokenClass', 'javadocTag', 'oracleType',
                'projectName', 'packageName', 'className', 'methodSourceCode', 'methodJavadoc', 'classJavadoc',
                'classSourceCode', 'oracleId', 'label'
            ]
            # Reindex the DataFrame with the new order
            self._df_dataset = self._df_dataset.reindex(columns=new_columns_order)

        if self._classification_type == ClassificationType.CATEGORY_PREDICTION:
            # Keep only a single instance for each combination of oracleId and oracleSoFar
            self._df_dataset = self._df_dataset[self._df_dataset['label'] == 'True']

        # Remove method source code (keep only signature)
        self._df_dataset['methodSourceCode'] = self._df_dataset['methodSourceCode'].str.split('{').str[0]

        # Delete the tgt labels from the input dataset, and others less relevant columns
        df_src = self._df_dataset.drop(['label', 'oracleId', 'projectName', 'classJavadoc', 'classSourceCode'], axis=1)
        # If the model predicts token classes, remove the token values from the input, else remove
        # the token classes from the input
        if self._tratto_model_type == TrattoModelType.TOKEN_CLASSES:
            df_src = df_src.drop(['token','tokenInfo'], axis=1)
            # Remove the tokenClass column if the model will predict the tokenClass as target
            if self._classification_type == ClassificationType.CATEGORY_PREDICTION:
                df_src = df_src.drop(['tokenClass'], axis=1)
        else:
            # Remove the token column if the model will predict the token as target
            if self._classification_type == ClassificationType.CATEGORY_PREDICTION:
                df_src = df_src.drop(['token'], axis=1)
            # Remove the tokenInfo column if the classificator is a decoder
            if self._transformer_type == TransformerType.DECODER and not self._classification_type == ClassificationType.LABEL_PREDICTION:
                df_src = df_src.drop(['tokenInfo'], axis=1)

        # The apply function maps each row of the src dataset with multiple columns, to
        # a row with a single column containing the concatenation of the strings of each
        # original column, using a token as a separator.
        #
        # For example:
        #
        #   1. Given the first row of the dataset:
        #
        #         projectName                                 "commons-collections4-4.1"
        #         packageName                          "org.apache.commons.collections4"
        #         className                                                    "Equator"
        #         javadocTag                "@return whether the two objects are equal."
        #         methodJavadoc      "/**\n     * Evaluates the two arguments for th..."
        #         methodSourceCode                         "boolean equate(T o1, T o2);"
        #         classJavadoc       "/**\n * An equation function, which determines..."
        #         oracleSoFar                                                         ""
        #         token                                                              "("
        #         tokenClass                                        "OpeningParenthesis"
        #         tokenInfo                                                           ""
        #         notes                                                               ""
        #
        #   2. The statement gets the values of each column in an array (row.values)
        #
        #         ["commons-collections4-4.1","org.apache.commons.collections4",...,"",""]
        #
        #   3. The join method concatenates all the values in the array into a string,
        #      using a special token (the *sep_token*) as separator.
        #
        #         commons-collections4-4.1<s>org.apache.commons.collections4<s>...<s>OpeningParenthesis<s><s>
        #
        # The result of step (3) represents the content of the unique column of the new
        # map row. The process is repeated for each row in the src dataset.
        df_src_concat = df_src.apply(lambda row: self._tokenizer.sep_token.join(row.values), axis=1)

        # The pandas dataframe is transformed in a list of strings: each string is an input to the model
        src = df_src_concat.to_numpy().tolist()
        # Get the list of target values from the dataframe
        if self._transformer_type == TransformerType.DECODER:
            if self._classification_type == ClassificationType.CATEGORY_PREDICTION:
                if self._tratto_model_type == TrattoModelType.TOKEN_CLASSES:
                    tgt = self._df_dataset["tokenClass"].values.tolist()
                elif self._tratto_model_type == TrattoModelType.TOKEN_VALUES:
                    tgt = self._df_dataset["token"].values.tolist()
            elif self._classification_type == ClassificationType.LABEL_PREDICTION:
                tgt = self._df_dataset["label"].values.tolist()
        else:
            if self._classification_type == ClassificationType.CATEGORY_PREDICTION:
                classes_ids_dict = self.get_encoder_classes_ids()
                if self._tratto_model_type == TrattoModelType.TOKEN_CLASSES:
                    tgt = list(map(lambda t: classes_ids_dict[t], self._df_dataset["tokenClass"].values.tolist()))
                elif self._tratto_model_type == TrattoModelType.TOKEN_VALUES:
                    tgt = list(map(lambda t: classes_ids_dict[t], self._df_dataset["token"].values.tolist()))
            elif self._classification_type == ClassificationType.LABEL_PREDICTION:
                labels_ids_dict =  self.get_encoder_labels_ids()
                tgt = list(map(lambda t: labels_ids_dict[t], self._df_dataset["label"].values.tolist()))
        # Split the dataset into training and test sets with stratified sampling (given imbalanced dataset), based on target classes
        self._src, self._src_test, self._tgt, self._tgt_test = train_test_split(src, tgt, test_size=self._test_ratio)  # stratify=tgt)

    def _load_dataset(
            self,
            d_path: str
    ):
        """
            The method applies the dataset preprocessing. It loads dataset from
            specified `d_path`. Drops empty `label` column and fills null values
            with empty strings.

            Parameters
            ----------
            d_path: str
                The path to the dataset

            Returns
            -------
            df_dataset: pandas.DataFrame
                A pandas DataFrame representation of the dataset
        """
        # list of partial dataframes
        dfs = []
        # Datasets path
        oracles_dataset = os.path.join(d_path)
        # Collects partial dataframes from oracles
        for file_name in os.listdir(oracles_dataset):
            df = pd.read_json(os.path.join(oracles_dataset, file_name))
            dfs.append(df)
        df_dataset = pd.concat(dfs)
        df_dataset.reset_index(drop=True, inplace=True)
        return df_dataset

    def _tokenize_dataset(
            self,
            datapoints: Tuple[List[str], List[List[float]]]
    ):
        """
        The method tokenizes the input and target datapoints passed to the function

        Parameters
        ----------
        datapoints: List[Tuple[str,Union[str,float]]]
            A list of datapoints  (tuples input and targets).

        Returns
        -------
        tokenized_batches : Tuple[List[int],List[float],List[float]]]
            A tuple of 3 lists, where the first list represents the tokenized strings of the input datapoints of the
            dataset, the second list is the tensor of the corresponding attention-masks, the third element is the tensor
            of the corresponding targets.
        """
        # Extracts the inputs datapoints from the dataset
        inputs = datapoints[0]
        # Extracts the corresponding targets datapoints from the dataset
        targets = datapoints[1]
        # Tokenize the input and the target datapoints
        # The method generate a dictionary with two keys:
        #
        #   t_src_dict = {
        #       "input_ids": [[t_i_1_1,...,t_i_1_n],...,[t_i_k_1,...,t_i_k_n]],
        #       "attention_mask": [[m_1_1,...,m_k_n],...,[m_k_1,...,m_k_n]]
        #   }
        #
        # where each element in the *input_ids* list is a list of tokenized words
        # (the words of an input datapoint), while each element in the *attention
        # masks* is the corresponding mask vector to distinguish the real tokens
        # from the padding tokens. In the example, t_i_x_y is the y tokenized word
        # of the input datapoint x, and m_x_y is a boolean value that states if
        # the token y is a real word or a padding token.
        #
        t_src_dict = self._tokenizer.batch_encode_plus(
            inputs,
            max_length=512,
            truncation=True,
            padding=True,
            return_tensors="pt"
        )
        if self._transformer_type == TransformerType.DECODER:
            t_tgt_dict = self._tokenizer.batch_encode_plus(
                targets,
                max_length=8,
                truncation=True,
                padding=True,
                return_tensors="pt"
            )
        # Transform the list into a tensor stack
        #
        #   t_src_dict['input_ids'] = [[t_i_1_1,...,t_i_1_n],...,[t_i_k_1,...,t_i_k_n]]
        #
        #   t_inputs = tensor([
        #           [t_i_1_1,...,t_i_1_n],
        #                   ...
        #           [t_i_k_1,...,t_i_k_n]
        #   ])
        #
        # this is the structure accepted by the DataLoader, to process the dataset
        t_inputs = torch.stack([ids.clone().detach() for ids in t_src_dict['input_ids']])
        t_inputs_attention_masks = torch.stack([mask.clone().detach() for mask in t_src_dict['attention_mask']])
        if self._transformer_type == TransformerType.DECODER:
            t_targets = torch.stack([ids.clone().detach() for ids in t_tgt_dict['input_ids']])
        else:
            t_targets = torch.stack([torch.tensor(t) for t in targets])
        # Generate the tokenized dataset
        tokenized_dataset = (t_inputs, t_inputs_attention_masks, t_targets)
        # Return the tokenized dataset
        return tokenized_dataset