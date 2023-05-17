import os
import math
import random
from typing import Type

import pandas as pd
import numpy as np
import torch
from functools import reduce
from collections import Counter
from torch.utils.data import TensorDataset, ConcatDataset
from transformers import AutoTokenizer
from src.enums.DatasetType import DatasetType
from src.enums.BatchType import BatchType
from src.enums.FileFormat import FileFormat
from src.enums.FileName import FileName


class DataProcessor:
    """
    The *DataProcessor* class takes care of pre-processing the raw datasets to create
    the final datasets, efficiently sorted (with customizable criteria) and tokenized.
    The datasets generated by the class can be feed to the *Dataset* and *DataLoader*
    PyTorch classes to easily load the data and prepare the batches to feed the model.

    Parameters
    ----------
    tokenizer : RobertaTokenizerFast
        The instance of tokenizer used to tokenize the input dataset
    batch_size: int
        The length of each batch of the training and validation dataset
    training_ratio: float
        A value in the interval [0.0,1.0] that represents the percentage of datapoints

    Attributes
    ----------
    src : list[str]
        The list of input datapoints (strings concatenated).
        Initially set to None
    o_ids : list[int]
        The list of oracle ids that identifies to which oracle each input datapoint refer.
        Initially set to None
    tgt : list[int]
        The list of expected values (0 or 1) for each datapoint in input to the model.
        Initially set to None
    processed_dataset: dict
        Dictionary of the processed dataset:
            - d_sorted: contains the tuples of (input, oracle_id, target) datapoints
              after the original dataset has been sorted according to the selected criteria
            - b_train_tokenized: contains the list of batches for the training dataset,
              after the original dataset has been splitted (according to *training_ratio*
              value), grouped in batches, and tokenized
            - b_val_tokenized: contains the list of batches for the validation dataset,
              after the original dataset has been splitted (according to *training_ratio*
              value), grouped in batches, and tokenized
            - b_train: contains the list of batches for the training dataset, after
              the original dataset has been splitted (according to *training_ratio*
              value) and grouped in batches, but not tokenized yet
            - b_val: contains the list of batches for the training dataset, after
              the original dataset has been splitted (according to *training_ratio*
              value) and grouped in batches, but not tokenized yet
            - b_short_id: given the number of datapoints in the original dataset,
              the division in batches could be with rest. This means that one of the
              batch will contains less datapoints than the others. Given that we
              shuffle the batches during the dataprocessing, the class have to keep
              track of the short batch so that it can be processed properly, during
              the generation of the training and validation datasets
            - rand_short_id: it is a boolean value that establish if in case of
              a short batch, it must be kept as the last of the batches in the list,
              (rand_short_id set to False) or it can have a random position within
              the list (rand_short_id set to True)
    """
    def __init__(
            self,
            d_path: str,
            batch_size: int,
            training_ratio: float,
            tokenizer: any
    ):
        self._d_path = d_path
        self._tokenizer = tokenizer
        self._batch_size = batch_size
        self._training_ratio = training_ratio
        self._df_dataset = self._load_dataset(d_path)
        self._src = None
        self._o_ids = None
        self._tgt = None
        self._processed_dataset = {
            "d_sorted": [],
            "b_train_tokenized": [],
            "b_val_tokenized": [],
            "b_train": [],
            "b_val": [],
            "b_short_id": -1,
            "rand_short_id": True
        }

    def get_o_ids(self):
        """
        The method returns a copy of the list of the oracle ids associated to
        the input datapoints

        Returns
        -------
        o_ids : list[str]
            A copy of the list of oracle ids associated to the input datapoints
        """
        o_ids = [*self._o_ids]
        return o_ids

    def get_src(self):
        """
        The method returns a copy of the list of input datapoints

        Returns
        -------
        src : list[str]
            A copy of the list of input datapoints
        """
        src = [*self._src]
        return src

    def get_tgt(self):
        """
        The method returns a copy of the list of the targets associated to
        the input datapoints

        Returns
        -------
        tgt : list[str]
            A copy of the list of targets associated to the input datapoints
        """
        tgt = [*self._tgt]
        return tgt

    def get_tokenized_dataset(self, d_type):
        """
        The method returns the final processed tokenized (training or validation) dataset

        Parameters
        ----------
        d_type: DatasetType
            The dataset type that the method must return
                - DatasetType.TRAINING for the training dataset
                - DatasetType.VALIDATION for the validation dataset

        Returns
        -------
        t_dataset : TensorDataset
            A PyTorch TensorDataset composed of three tensors stack:
                - the first tensor stack representing the stack of tokenized input
                  datapoints of the whole sorted dataset
                - the second tensor stack representing the stack of attention masks
                  (each index corresponding to the index of the input datapoints in
                  the first tensor)
                - the third tensor stack representing the list of expected target
                  outputs (each index corresponding to the index of the input
                  datapoints in the first tensor)
        """
        # The list of batches of the tokenized (training or validation) datapoints
        if d_type == DatasetType.TRAINING:
            b_tokenized= self._processed_dataset["b_train_tokenized"]
        elif d_type == DatasetType.VALIDATION:
            b_tokenized= self._processed_dataset["b_val_tokenized"]
        else:
            raise Exception(f"Unrecognized DataType value: {d_type}")

        t_dataset_list = []
        # The batches are composed of tuples of (inputs, attention_masks, targets) s
        # tacks, where each element of the tuple is a stack of n datapoints, with
        # 1<=n<=*BATCH_SIZE*
        #
        #       t_batch = (
        #           [
        #               [t_i_1_1,...,t_i_1_n],
        #                        ...
        #               [t_i_k_1,...,t_i_k_n]
        #           ],
        #           [
        #               [m_1_1,...,m_1_n],
        #                        ...
        #               [m_k_1,...,m_k_n]
        #           ],
        #           [
        #               [t_1],
        #                ...
        #               [t_n]
        #           ]
        #       )
        #
        for t_batch in b_tokenized:
            # The list of inputs of the current batch
            t_src_batch = t_batch[0]
            # The list of attention masks of the current batch
            t_mask_batch = t_batch[1]
            # The list of targets of the current batch
            t_tgt_batch = t_batch[2]
            # Generate a dataset of the batch
            dataset_batch = TensorDataset(
                t_src_batch,
                t_mask_batch,
                t_tgt_batch
            )
            # Add the datasets of batches
            t_dataset_list.append(dataset_batch)
        # Concatenates the datasets of batches in a single dataset
        t_dataset = ConcatDataset(t_dataset_list)
        # return the dataset
        return t_dataset

    def processing(
            self,
            batch_type: Type[BatchType]
    ):
        """
        This represents the core method of the class. Firstly, it sorts and maps the
        original dataset into batches, according to the criterion passed as parameter
        to the method itself (*batch_type*). Then, it splits the list of batches into
        the training and validation datasets. Finally it tokenizes the batches of the
        training and validation datasets

        Parameters
        ----------
        batch_type: BatchType
          The criterion type, according to which sort the input datapoints and generate
          the batches for the training and the validation datasets
        """
        # The dataset is sorted according to the batch type the PyTorch Dataloader will
        # have to pruduce
        if batch_type == BatchType.HETEROGENEOUS:
          self._sort_dataset_heterogeneously()
        elif batch_type == BatchType.OMOGENEOUS:
          self._sort_dataset_omogeneously()
        elif batch_type == BatchType.RANDOM:
          self._sort_dataset_randomly()
        elif batch_type == BatchType.SORTED_BY_LENGTH:
          self._sort_dataset_by_input_length()
        else:
          raise Exception("Batch type not recognized.")
        # The sorted dataset is grouped in batches, and the batches are splitted in
        # training and validation datasets
        self._generate_train_val_batches()
        # The batches of datapoints in the training and validation datasets are tokenized
        self._tokenize_batches()

    def pre_processing(self):
        # drop column id (it is not relevant for training the model)
        self._df_dataset = self._df_dataset.drop(['id'], axis=1)
        # map empty cells to empty strings
        self._df_dataset.fillna('', inplace=True)
        # specify the type of each column in the dataset
        self._df_dataset = self._df_dataset.astype({
            'label': 'bool',
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
        # delete the oracle ids and the tgt labels from the input dataset
        df_src = self._df_dataset.drop(['oracleId','oracleType','label'], axis=1)
        # create a dataframe for the oracle ids (we convert the boolean values to int64)
        df_oracle_ids = self._df_dataset[['oracleId']]
        # create a dataframe for the target labels (the apply function convert the
        # boolean labels to 0s and 1s
        df_tgt = self._df_dataset[['label']].replace({True: 1, False: 0})

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
        #      using a special token (the *cls_token*) as separator.
        #
        #         commons-collections4-4.1<s>org.apache.commons.collections4<s>...<s>OpeningParenthesis<s><s>
        #
        # The result of step (3) represents the content of the unique column of the new
        # map row. The process is repeated for each row in the src dataset.
        df_src_concat = df_src.apply(lambda row: self._tokenizer.cls_token.join(row.values), axis=1)
        # The pandas dataframe is transformed in a list of strings: each string is a input
        # to the model
        self._src = df_src_concat.to_numpy().tolist()
        # We also transform the oracle ids and the targets to a list
        self._o_ids = df_oracle_ids.to_numpy().tolist()
        tgt_numpy = df_tgt.to_numpy().ravel()
        tgt_classes = tgt_numpy.max() + 1
        one_hot_tgt = np.zeros((len(tgt_numpy), tgt_classes))
        one_hot_tgt[np.arange(len(tgt_numpy)), tgt_numpy] = 1
        self._tgt = one_hot_tgt.tolist()

    def _generate_train_val_batches(self):
        """
        The method splits the datapoints of the sorted dataset, and generate the batches
        for the training and the validation datasets.
        """
        # The number of datapoints that compose the entire dataset
        dp_len = len(self._processed_dataset["d_sorted"])
        # The number of datapoints that have to be assigned to the training dataset
        dp_train_len = math.floor(dp_len * self._training_ratio)
        # The number of datapoints that have to be assigned to the validation dataset
        dp_val_len = dp_len - dp_train_len
        # The total number of batches, given the total number of datapoints and the
        # batch size. The division is rounded to the smallest integer greater than or
        # equal to the result, because if there is a rest, the remaining datapoints
        # have to be grouped in an additional batch
        b_len = math.ceil(dp_len / self._batch_size)
        # The total number of batches, given the number of datapoints assigned to the
        # training dataset and the batch size.
        b_train_len = math.ceil(dp_train_len / self._batch_size)
        # The total number of batches, given the number of datapoints assigned to the
        # validation dataset and the batch size
        b_val_len = math.ceil(dp_val_len / self._batch_size)
        # the whole dataset is grouped in batches
        b_sorted_list = self._map_dataset_to_batches(b_len, dp_len)
        # The batches of the whole dataset are shuffled
        b_ids_shuffled_list = self._shuffle_sorted_batches_ids(b_len)

        # The list of batches that compose the training dataset
        b_train_list = []
        # The list of batches that compose the validation dataset
        b_val_list = []
        # The for loop assigns the *training_ratio* percentage of the shuffled batches
        # to the training dataset, while the remaining (1 - *training_ratio*) percentage
        # to the validation dataset. The number of batches assigned to the training
        # dataset is rounded up (for example if the number of batches is 22, and the
        # training ratio is 0.8 the number of batces assigned to the training is
        # 0.8 * 22 = 17.6 --> 18). The reason is that the number of batches assigned to
        # the training an validation datasets must be an integer. The number of batches
        # assigned to the validation dataset would be equal to (1 - 0.8) * 22 = 4.4 --> 5.
        # In principle, this seems to lead to an inconsistent result because (given
        # the example) the total number of batches would become:
        #
        #   b_train_len + b_val_len = 18 + 5 = 23 > 22
        #
        # But we can consider the fact that the "extra" batch is a batch shared between
        # both the training and the validation datasets. The 80% of its content would
        # be assigned to the training dataset, while the remaining 20% to the validation
        # dataset.
        # Moreover, the algorithm guarantees that the eventual batch splitted among the
        # training and the validation datasets will always be a full batch, composed of
        # BATCH_SIZE datapoints (if the whole dataset is composed of only one single
        # batch, this batch is used instead, even if it is not full).
        for idx, b_id in enumerate(b_ids_shuffled_list[:b_train_len],1):
            if not idx == b_train_len:
                b_train_list.append(b_sorted_list[b_id])
            else:
                # Check if the batch division has rest
                if dp_train_len % self._batch_size == 0:
                    # If the rest is 0 the whole batch is added to the training dataset
                    b_train_list.append(b_sorted_list[b_id])
                else:
                    # If the rest is not 0 the batch is splitted among the training
                    # and the validation datasets
                    rest = dp_train_len % self._batch_size
                    # The *training_ratio* percentage of the full batch is assigned to
                    # the training dataset
                    b_partial_train = b_sorted_list[b_id][:rest]
                    # The remaining datapoints of the full batch is assigned to the
                    # validation dataset
                    b_partial_val = b_sorted_list[b_id][rest:]
                    # Add the partially full batch to the training dataset batch list
                    b_train_list.append(b_partial_train)
                    # Add the remaining part of the batch to the validation dataset
                    # batch list
                    b_val_list.append(b_partial_val)
        # The for loop assigns the remaining batches to the validation dataset
        for b_id in b_ids_shuffled_list[b_train_len:]:
            b_val_list.append(b_sorted_list[b_id])
        # The generated lists of batches are stored in the *processed_dataset* dict
        # of the instance of the class
        self._processed_dataset["b_train"] = b_train_list
        self._processed_dataset["b_val"] = b_val_list

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
        # read the dataset
        dfs = []
        for file_name in os.listdir(os.path.join(d_path)):
            df = pd.read_json(os.path.join(d_path, file_name))
            dfs.append(df)
        df_dataset = pd.concat(dfs)
        return df_dataset

    def _map_dataset_to_batches(
            self,
            b_len: int,
            dp_len: int
    ):
        """
        The method maps the datapoints of the sorted dataset into batches.

        Parameters
        ----------
        b_len: int
            The total number of batches within the whole dataset
        dp_len: int
            The total number of datapoints within the whole dataset
        """
        # The list of the whole sorted dataset
        d_sorted = self._processed_dataset["d_sorted"]
        # The rest of the division between the whole number of datapoints
        # and the batch size. The rest gives the number of datapoints in
        # the last batch (if the rest is 0 all the batches are full,
        # otherwise, there is a batch that will contain *rest* datapoints,
        # with rest < BATCH_SIZE
        rest = dp_len % self._batch_size
        # Boolean flag (True if there is a batch not full, False otherwise)
        rest_flag = not rest == 0

        # Check if there is a batch not full and the selected sorting
        # algorithm let's to have the partially full batch in a random
        # position
        if rest_flag and self._processed_dataset["rand_short_id"]:
            # Select the random index of the partially full batch
            short_batch_id = random.randrange(0,b_len)
            # Store the index of the partially full batch
            self._processed_dataset["b_short_id"] = short_batch_id
        else:
            # If the position of the partially full batch cannot be
            # random, get the position that it must have in the
            # list of batches
            short_batch_id = self._processed_dataset["b_short_id"]
        # Initialization of the batch list
        batches = []
        # Pointer of the position in the whole sorted dataset
        pointer = 0
        # The for cycle split the whole dataset into batches of size
        # *BATCH_SIZE*. If the index of the current batch is equal
        # to the index of the partially fulled batch (*short_batch_id*),
        # a batch of size *rest* is generated
        for i in range(b_len):
          new_pointer = pointer
          if rest_flag and i == short_batch_id:
            # Compute the end position of the current batch in the whole
            # dataset, if the current batch is the partially full batch
            # of size *rest*)
            new_pointer += rest
          else:
            # Compute the end position of the current batch in the whole
            # dataset, if the current batch is a full batch of size
            # *BATCH_SIZE*)
            new_pointer += self._batch_size
          # Extract batch datapoints from the whole sorted dataset
          batch = d_sorted[pointer: new_pointer]
          # Update pointer to the position of the whole sorted dataset
          # not yet splitted
          pointer = new_pointer
          # Add the batch to the list
          batches.append(batch)
        # Return the batch list
        return batches

    def _shuffle_sorted_batches_ids(
            self,
            b_len: int,
            seed: int = 42
    ):
        """
        The method shuffle the list of sorted batches. If there is a
        partially full batch within the list, it is always positioned
        at the end of the list (otherwise the following partition of
        the batches between the training and the validation)

        Parameters
        ----------
        b_len: int
            The total number of batches in the whole dataset
        seed: int
            The seed through which the batch indices are randomly shuffled.
            This parameter let to reproduce the same shuffled batches.
        """
        # If the list has a partially full batch, get the index
        # The value is -1 if there is not a short batch (all the
        # batches in the list are full)
        short_batch_id = self._processed_dataset["b_short_id"]
        # Generate a sorted list of indices
        b_ids = [i for i in range(b_len)]
        # Check if the partially full batch exists
        if not short_batch_id == -1:
            # Remove the index of the short batch
            b_ids.remove(short_batch_id)
        # Set the seed to reproduce the same shuffle in future
        random.seed(seed)
        random.shuffle(b_ids)
        # Check if the partially full batch exists
        if not short_batch_id == -1:
            # Add the index of the short batch at the end of the list
            b_ids.append(short_batch_id)
        # Return the shuffled list of batch indices
        return b_ids

    def _sort_dataset_heterogeneously(self):
        """
        The method sorts the original dataset distributing the datapoints heterogenously.
        This sorting will let the dataloader to produce batches that minimize the
        number of datapoints within each batch, referring to the same oracle (ideally
        only 0 or 1 datapoint referring to the same oracle, for each batch)
        """
        # Let's first create tuples of input, oracle ids and targets.
        # This operation is necessary to maintain the conformity between the inputs,
        # the oracle ids, and the targets that compose the dataset.
        # Otherwise, if we only sort the inputs sentences, without sorting
        # the corresponding oracle ids and targets, we lose the corrispondence between
        # the input, the oracle id, and expected output.
        # Therefore, given the list of the sentences, the list of the oracle ids, and
        # the list of targets:
        #
        #     self._src = [s_1,s_2,...,s_n], where s_i is the input i within the dataset
        #     self._o_ids = [o_1,o_2,...,o_n], where o_i is the oracle id i associated to the input i
        #     self._tgt = [t_1,t_2,...,t_n], where t_i is the label associated to the input i
        #
        # the zip statement produces a list of (s_i, o_i, t_i) tuples:
        #
        #     src_tgt_zip = [(s_1,o_1,t_1),(s_2,o_1,t_2),...,(s_n,o_1,t_n)]
        #
        s_o_t_zip = list(zip(self._src, self._o_ids, self._tgt))

        # Length of the whole dataset
        dp_len = len(self._src)
        # Number of total batches, given the whole dataset
        b_len = math.ceil(dp_len / self._batch_size)
        # Dictionary of the number of occurrences of each oracle id within the whole dataset.
        # The Counter class, given a list, computes the number of occurrences for each different
        # value in the list. For example, given the list
        #
        #       o_ids = [1,2,2,4,3,2,1,3,2,4]
        #
        # The Counter class returns the following dictionary:
        #
        #       occurrences_counter = {
        #           1: 2,
        #           2: 4,
        #           3: 2,
        #           4: 2
        #       }
        #
        occurrences_counter = Counter(self._o_ids)
        # Dictionary of empty lists, one for each oracle id in the dataset. each list, will be
        # filled with the datapoints that refer to the same oracle id. Given the previous
        # example of *o_ids* and *occurrences_counter*, the *occurrence* dictionary will be
        # initialized in this way:
        #
        #       occurrences = {
        #           1: [],
        #           2: [],
        #           3: [],
        #           4: []
        #       }
        #
        occurrences = { k: [] for k in occurrences_counter.keys()}
        # The list of the final sorted tuples (s_i,o_i,t_i)
        s_o_t_zip_sorted = []

        # The for cycle fills the occurrences of tuples of datapoints in the dataset that
        # refers to the same oracle id
        #
        #       occurrences = {
        #           1: [(s_1_1,o_1_1,t_1_1),(s_1_2,o_1_2,t_1_2)]
        #           2: [(s_2_1,o_2_1,t_2_1),...,(s_2_4,o_2_4,t_2_4)],
        #           3: [(s_3_1,o_3_1,t_3_1),(s_3_2,o_3_2,t_3_2)],
        #           4: [(s_4_1,o_4_1,t_4_1),(s_4_2,o_4_2,t_4_2)]
        #       }
        #
        for input, oracle_id, target in s_o_t_zip:
            occurrences[oracle_id].append((input,oracle_id,target))

        # The for cycle sorts the dataset simulating the filling of the batches of the
        # whole dataset, trying to create heterogeneous batches
        for i in range(b_len):
            #  The list of distinct oracle ids still available
            o_avail = list(occurrences_counter.keys())
            # Fills the batch of *BATCH_SIZE* length
            for j in range(self._batch_size):
                #  Check id the list of distinct oracle ids still available is empty
                if len(o_rand) == 0:
                    # If the list is empty try to refill it with the distinct oracle ids
                    # still available in the counter dictionary
                    o_avail = list(occurrences_counter.keys())
                    # Check if the list is empty even after the re-filling
                    if o_avail == 0:
                        # If the list is empty stops the algorithm
                        break
                o_id_rand = random.choice(o_avail)
                o_rand = random.choice(occurrences[o_id_rand])
                o_avail.remove(o_id_rand)
                occurrences[o_id_rand].remove(o_rand)
                occurrences_counter[o_rand] -= 1
                if occurrences_counter[o_rand] == 0:
                  assert len(occurrences[o_id_rand]) == 0, "Occurences should be empty."
                  del occurrences_counter[o_id_rand]
                s_o_t_zip_sorted.append(o_rand)
        self._processed_dataset["d_sorted"] = s_o_t_zip_sorted
        self._processed_dataset["b_short_id"]: b_len - 1
        self._processed_dataset["rand_short_id"]: False

    def _sort_dataset_omogeneously(self):
        """
        The method sorts the dataset in a way that let the dataloader to produce
        batches with the highest number of datapoints referring to the same oracle
        """
        # Let's first create tuples of input, oracle ids and targets.
        # This operation is necessary to maintain the conformity between the inputs,
        # the oracle ids, and the targets that compose the dataset.
        # Otherwise, if we only sort the inputs sentences, without sorting
        # the corresponding oracle ids and targets, we lose the corrispondence between
        # the input, the oracle id, and expected output.
        # Therefore, given the list of the sentences, the list of the oracle ids, and
        # the list of targets:
        #
        #     self._src = [s_1,s_2,...,s_n], where s_i is the input i within the dataset
        #     self._o_ids = [o_1,o_2,...,o_n], where o_i is the oracle id i associated to the input i
        #     self._tgt = [t_1,t_2,...,t_n], where t_i is the label associated to the input i
        #
        # the zip statement produces a list of (s_i, o_i, t_i) tuples:
        #
        #     src_tgt_zip = [(s_1,o_1,t_1),(s_2,o_1,t_2),...,(s_n,o_1,t_n)]
        #
        s_o_t_zip = list(zip(self._src, self._o_ids, self._tgt))
        # The tuples are sorted using as key the second element of each tuple, which means
        # the oracle id of each input.
        s_o_t_zip_sorted = sorted(s_o_t_zip, key=lambda t: t[1])
        self._processed_dataset["d_sorted"] = s_o_t_zip_sorted

    def _sort_dataset_randomly(
            self,
            seed: int = 42
    ):
        """
        The method shuffle the original dataset in a random way

        Parameters
        ----------
        seed: int
          The seed through which the datapoints of the dataset are randomly shuffled.
          This parameter let to reproduce the same shuffled batches.
        """
        # The batches are randomly sampled from the dataset
        s_o_t_zip = list(zip(self._src, self._o_ids, self._tgt))
        # Set the seed to reproduce the same shuffle in future
        random.seed(seed)
        # Shuffle the datasets tuples randomly
        random.shuffle(s_o_t_zip)
        self._processed_dataset["d_sorted"] = s_o_t_zip

    def _sort_dataset_by_input_length(self):
        """
        The method sorts the dataset in a way that let the dataloader to produce
        batches of data composed of inputs with similar length. In this way, when
        the class will tokenize and embed the batches, it will reduce the padding in each
        batch, improving the performance of the model training (padding is used to
        create sentences with the same length, but they do not contribute to the
        training of the model and they introduce overhead)
        """
        # The method sort the dataset in a way that let the dataloader to produce
        # batches of data composed of inputs with similar length. In this way, when
        # we will tokenize and embed the batches, we will reduce the padding in each
        # batch, improving the performance of the model training (padding is used to
        # create sentences with the same length, but they do not contribute to the
        # training of the model and they introduce overhead).
        #
        # Let's first create tuples of input, oracle ids and targets.
        # This operation is necessary to maintain the conformity between the inputs,
        # the oracle ids, and the targets that compose the dataset.
        # Otherwise, if we only sort the inputs sentences, without sorting
        # the corresponding oracle ids and targets, we lose the corrispondence between
        # the input, the oracle id, and expected output.
        # Therefore, given the list of the sentences, the list of the oracle ids, and
        # the list of targets:
        #
        #     self._src = [s_1,s_2,...,s_n], where s_i is the input i within the dataset
        #     self._o_ids = [o_1,o_2,...,o_n], where o_i is the oracle id i associated to the input i
        #     self._tgt = [t_1,t_2,...,t_n], where t_i is the label associated to the input i
        #
        # the zip statement produces a list of (s_i, o_i, t_i) tuples:
        #
        #     src_tgt_zip = [(s_1,o_1,t_1),(s_2,o_1,t_2),...,(s_n,o_1,t_n)]
        #
        s_o_t_zip = list(zip(self._src, self._o_ids, self._tgt))
        # The tuples are sorted using as key the first element of each tuple, which means
        # the oracle id of each input.
        s_o_t_zip_sorted = sorted(s_o_t_zip, key=lambda t: t[0])
        self._processed_dataset["d_sorted"] = s_o_t_zip_sorted

    def _tokenize_batches(self):
        """
        The method tokenizes the input datapoints of the batches that composes the
        training and validation datasets
        """
        # The batch list of the training dataset
        b_train = self._processed_dataset["b_train"]
        # The batch list of the validation dataset
        b_val = self._processed_dataset["b_val"]
        for d_type in DatasetType:
            if d_type == DatasetType.TRAINING:
                batches = b_train
            elif d_type == DatasetType.VALIDATION:
                batches = b_val
            else:
                raise Exception(f"Unrecognized DataType value: {d_type}")
            for batch in batches:
                # Extracts the inputs datapoints from the batch
                b_inputs = [ t[0] for t in batch ]
                # Extracts the corresponding targets datapoints from the batch
                b_targets = [ t[2] for t in batch ]
                # Computes the length of the longest input datapoint within the batch
                max_len = reduce(lambda max_len, s: len(s) if len(s) > max_len else max_len, b_inputs,0)
                # Tokenize the inputs datapoints of the batch
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
                # the token y is a real word or a padding token
                #
                t_src_dict = self._tokenizer(
                  b_inputs,
                  max_length=max_len,
                  padding='max_length',
                  truncation=False
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
                t_inputs = torch.stack([torch.tensor(ids) for ids in t_src_dict['input_ids']])
                # Transform the list into a tensor stack
                t_attention_masks = torch.stack([torch.tensor(mask) for mask in t_src_dict['attention_mask']])
                # Transform the targets into a tensor list
                targets_tensor = torch.tensor(b_targets)
                if d_type == DatasetType.TRAINING:
                    # Add the tuple representing the tokenized batch to the list of training dataset
                    self._processed_dataset["b_train_tokenized"].append((t_inputs, t_attention_masks, targets_tensor))
                elif d_type == DatasetType.VALIDATION:
                    # Add the tuple representing the tokenized batch to the list of validation dataset
                    self._processed_dataset["b_val_tokenized"].append((t_inputs, t_attention_masks, targets_tensor))
                else:
                    raise Exception(f"Unrecognized DataType value: {d_type}")
