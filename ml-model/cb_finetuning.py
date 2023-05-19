#!/usr/bin/env python
# coding: utf-8

import os
import numpy as np
import torch
import timeit
import math
import json
import torch.optim as optim
from functools import reduce
from torch.utils.data import DataLoader, SequentialSampler
from torch.nn import CrossEntropyLoss
from transformers import AutoTokenizer

from src.enums.BatchType import BatchType
from src.enums.DatasetType import DatasetType
from src.enums.DeviceType import DeviceType
from src.enums.FileFormat import FileFormat
from src.enums.FileName import FileName
from src.enums.HyperParameter import HyperParameter
from src.enums.Path import Path
from src.model.DataProcessor import DataProcessor
from src.model.OracleClassifier import OracleClassifier
from src.model.OracleTrainer import OracleTrainer
from src.model.Printer import Printer
from src.utils import utils

if __name__ == "__main__":
    Printer.print_welcome()
    Printer.print_load_gpu()
    device = utils.connect_to_device(DeviceType.GPU)
    d_path = Path.INPUT_DATASET.value

    ## Tokenizer
    #
    # Loads `codebert-base` from `AutoTokenizer` to concatenate the input columns of
    # the dataframe with the *tokenizer.cls* separator token and tokenize the whole
    # input dataset
    #
    # Create instance of pretrained tokenizer
    tokenizer = AutoTokenizer.from_pretrained("microsoft/codebert-base")

    ## DataProcessor
    #
    # The **DataProcessor** class takes care of pre-processing the raw datasets to
    # create the final datasets, efficiently sorted (with customizable criteria) and tokenized.
    # The datasets generated by the **DataProcessor** class can be feed to the **Dataset** and
    # **DataLoader** PyTorch classes to easily load the data and prepare the batches to feed
    # the model.
    # The tokenized datasets are sorted in a way that let the **Dataloader** to generate the
    # final batches processing the datasets sequentially (using the **SequentialSampler**
    # PyTorch helper class).
    # Although already tokenized, the datasets contains elements of different size (implicitly
    # grouped by the length of the batches, previously defined by the hyperparameter **BATCH_SIZE**):
    # the reason is that, given the fact that we use a pretrained hugginface model and tokenizer,
    # we cannot rely on our own vocabulary and the standard PyTorch classes (such as **Field**,
    # **TranslationDataset**, and **Iterator**) to efficiently generate the tokenized batch in
    # a transparent way.
    # The tokenizer that we have to use relies on the codeBERT prebuilt vocabulary, and it tokenizes
    # the whole dataset padding the inputs according to several criteria (for example the maximum
    # length of the input in the dataset). This would mean that even input datapoints with short length
    # would be padded to the length of the maximum one, generating a lot of overhead and heavily
    # reducing the performance of the training and validation processes.
    # The tokenizer let to truncate the long inputs, but given our input datapoints, truncating the
    # input probably should not be an option. Indeed, by cutting off information, we may be removing
    # important context or information that the model needs to make accurate predictions. This can result
    # in decreased performance and reduced quality of the model's output.
    # With the **DataProcessor** class we sort the data according to a given criteria (defined when the
    # **DataProcessor** class is instantiated) and we already simulate the generation of batches so that
    # we can tokenize batches of data instead of the whole dataset, reducing the padding to the longest
    # input datapoint within each batch.
    # The *temporary* simulated batches are then flattened to a sorted list of datapoints so that when
    # they will be processed by the **DataLoader** sequentially, it will build the real tensor batches
    # in the same way.
    #
    # Create DataProcessor instance
    Printer.print_load_dataset()
    data_processor = DataProcessor(
        d_path,
        HyperParameter.BATCH_SIZE.value,
        HyperParameter.TRAINING_RATIO.value,
        tokenizer
    )
    # Pre-processing data
    Printer.print_pre_processing()
    data_processor.pre_processing()
    # Process the data
    data_processor.processing(BatchType.RANDOM)
    # Get the train and validation sorted datasets
    Printer.print_dataset_generation()
    train_dataset = data_processor.get_tokenized_dataset(DatasetType.TRAINING)
    val_dataset = data_processor.get_tokenized_dataset(DatasetType.VALIDATION)

    # DataLoader
    #
    # DataLoader is a pytorch class that takes care of shuffling/sampling/weigthed
    # sampling, batching, and using multiprocessing to load the data, in an efficient
    # and transparent way.
    # We define a dataloader for both the training and the validation dataset.
    # The dataloader generates the real batches of datapoints that we will use to
    # feed the model.
    # We use an helper PyTorch class, **SequentialSampler**, to create the batches
    # selecting the datapoints sequentially, from the training and validation datasets.
    # Indeed, we used the **DataProcessor** class to sort the dataset in specific way,
    # simulating the creation of batches of data before the **DataLoader**, minimizing
    # the padding (in the case of *BatchType.HOMOGENEOUS*) or maximizing the
    # diversity within the dataset (in the case of *BatchType.HETEROGENEOUS*). The
    # use of the **SequentialSampler** will guarantee to maintain this criteria for
    # the creation of the batches.
    #
    # Create instance of training and validation dataloaders
    dl_train = DataLoader(
        train_dataset,
        sampler = SequentialSampler(train_dataset),
        batch_size = HyperParameter.BATCH_SIZE.value
    )
    dl_val = DataLoader(val_dataset,
        sampler = SequentialSampler(val_dataset),
        batch_size = HyperParameter.BATCH_SIZE.value
    )

    # ## Model
    # The **OracleClassifier** represents our fine-tuned model.
    #
    # The architecture of the model is composed of:
    #
    # 1. The pre-trained codeBERT Transformer model
    # 2. A fully-connected layer that takes in input the output from the codebert model
    #    (which represents our hidden state) and maps this vector to a a vector of two
    #    elements (representing our 0 and 1 scores)
    # 3. The softmax activation function that transforms the output vector of the fully-
    #    connected layer into a vector of n probabilities, given the n classes of the
    #    classification task (in our case, 2). The softmax activation function is computed
    #    implicitly, during the training phase, by the loss function (the PyTorch
    #    **CrossEntropy** class, in our case). Therefore, the softmax is not a visible
    #    layer of the model.
    #
    # We compute the maximum length of the input datapoints, within the whole dataset\n# This let us to guarantee
    # that the model will process input data of this length\n# The +2 is given by the fact that the model add the
    # start token and the end token to each input of the model.
    src = data_processor.get_src()
    max_input_len = 512 #reduce(lambda max_len, s: len(s) if len(s) > max_len else max_len, src,0) + 2
    # Create instance of the model
    model = OracleClassifier(max_input_len)
    # The model is loaded on the gpu (or cpu, if not available)
    model.to(device)


    # ## Training
    #
    # The **OracleTrainer** class is an helper class that is used to perform the training
    # and the validation phases of the model. During the training phase, the model uses the
    # batches of data to compute the loss and update the weights to improve the accuracy of
    # the predictions. Instead, in the validation phase the trainer use batches of the
    # validation dataset to evaluate how the model is able to generalize on unseen data.
    # During the validation phase the weights of the model are not updated.
    #
    Printer.print_training_phase()
    # Adam optimizer with learning rate set with the value of the LR hyperparameter
    optimizer = optim.Adam(model.parameters(), lr=HyperParameter.LR.value)
    # The cross-entropy loss function is commonly used for classification tasks
    loss_fn = CrossEntropyLoss()
    # Instantiation of the trainer
    oracle_trainer = OracleTrainer(model, loss_fn, optimizer, dl_train, dl_val)

    stats = {}

    try:
        # Train the model
        stats = oracle_trainer.train(
            HyperParameter.NUM_EPOCHS.value,
            HyperParameter.NUM_STEPS.value,
            device
        )
    except RuntimeError as e:
        print("Runtime Exception...")
        if device.type == "cuda":
            torch.cuda.empty_cache()
        raise e

    # Check if the directory exists, to save the statistics of the training
    if not os.path.exists(Path.OUTPUT.value):
        # If the path does not exists, create it
        os.makedirs(Path.OUTPUT.value)
    # Save the statistics in json format
    with open(
        os.path.join(
            Path.OUTPUT.value,
            f"{FileName.LOSS_ACCURACY.value}.{FileFormat.JSON}"
        ),
        "w"
    ) as loss_file:
        data = {
            **stats,
            "batch_size": HyperParameter.BATCH_SIZE.value,
            "lr": HyperParameter.LR.value,
            "num_epochs": HyperParameter.NUM_EPOCHS.value
        }
        json.dump(data, loss_file)
    # Close the file
    loss_file.close()
    # ## Save the statistics and the trained model
    #
    # Saves the statistics for future analysis, and the trained model for future use or improvements.
    # Saving the model we save the values of all the weights. In other words, we create a snapshot of
    # the state of the model, after the training.
    Printer.print_save_model()
    torch.save(model, "tratto_model.pt")
    torch.save(model.state_dict(), os.path.join(Path.OUTPUT.value, "tratto_model_state_dict.pt"))

